package com.cenk.valocase.analytics.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cenk.valocase.analytics.AnalyticsProperties;
import com.cenk.valocase.analytics.domain.PlayerActivityEvent;
import com.cenk.valocase.analytics.domain.PlayerSession;
import com.cenk.valocase.analytics.repository.PlayerActivityEventRepository;
import com.cenk.valocase.analytics.repository.PlayerSessionRepository;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Backend-only session estimation from authenticated request activity. The
 * client sends no heartbeat/logout/lifecycle events, so sessions are estimates:
 * activity within {@code sessionTimeout} of the previous request continues the
 * open session; a longer gap closes it at its last observed activity
 * (INACTIVITY_TIMEOUT) and starts a new one. Writes happen on a single
 * background thread after the calling transaction commits, throttled per
 * account, so gameplay endpoints never gain latency and a tracking failure can
 * never fail a player request.
 */
@Service
@Slf4j
public class PlayerActivityService {

    public static final String END_REASON_INACTIVITY = "INACTIVITY_TIMEOUT";
    public static final String EVENT_SKINS_SOLD = "SKINS_SOLD";
    public static final String SOURCE_INVENTORY_SELL = "INVENTORY_SELL";

    private final PlayerSessionRepository sessionRepository;
    private final PlayerActivityEventRepository eventRepository;
    private final TransactionTemplate transactionTemplate;
    private final AnalyticsProperties properties;
    private final DiagnosticCounters diagnosticCounters;

    private final ConcurrentHashMap<UUID, Instant> lastTracked = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor;

    public PlayerActivityService(PlayerSessionRepository sessionRepository,
                                 PlayerActivityEventRepository eventRepository,
                                 PlatformTransactionManager transactionManager,
                                 AnalyticsProperties properties,
                                 DiagnosticCounters diagnosticCounters) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.diagnosticCounters = diagnosticCounters;
        // Built here rather than in a field initialiser so the rejection handler
        // can reach the injected counters.
        this.executor = new ThreadPoolExecutor(
                1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(1000),
                runnable -> {
                    Thread thread = new Thread(runnable, "player-activity-tracker");
                    thread.setDaemon(true);
                    return thread;
                },
                // Was DiscardPolicy, which dropped session writes in total silence.
                // A dropped task means the account exists but never gets a
                // player_sessions row, which is invisible in the daily views —
                // so it has to be said out loud.
                (runnable, rejectingExecutor) -> {
                    diagnosticCounters.recordSessionTaskDiscarded();
                    log.warn("session tracking task discarded: queueSize={} shutdown={}",
                            rejectingExecutor.getQueue().size(), rejectingExecutor.isShutdown());
                });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * Notes authenticated activity for an account. Throttled in memory, then
     * processed asynchronously after the surrounding transaction commits (so
     * the account row is always visible to the tracker). Never throws.
     */
    public void recordActivity(UUID accountId) {
        Instant now = Instant.now();
        Instant previous = lastTracked.get(accountId);
        if (previous != null && Duration.between(previous, now).compareTo(properties.getWriteThrottle()) < 0) {
            return;
        }
        lastTracked.put(accountId, now);
        log.debug("session tracking submitted for account {}", accountId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> track(accountId, now));
                }
            });
        } else {
            executor.execute(() -> track(accountId, now));
        }
    }

    /**
     * Records skins sold in the caller's transaction, so the event commits and
     * rolls back together with the sale and its wallet credit. Needed because
     * bulk sells delete the inventory rows and write one wallet transaction,
     * which would otherwise lose the per-item count.
     */
    @Transactional
    public void recordSkinsSold(UUID accountId, int quantity, long vpAmount, UUID referenceId) {
        PlayerActivityEvent event = new PlayerActivityEvent();
        event.setAccountId(accountId);
        event.setEventType(EVENT_SKINS_SOLD);
        event.setSource(SOURCE_INVENTORY_SELL);
        event.setReferenceId(referenceId);
        event.setQuantity(quantity);
        event.setVpAmount(vpAmount);
        event.setOccurredAt(Instant.now());
        eventRepository.save(event);
    }

    private void track(UUID accountId, Instant now) {
        try {
            transactionTemplate.executeWithoutResult(status -> upsertSession(accountId, now));
            diagnosticCounters.recordSessionCreationSuccess();
            log.debug("session tracking succeeded for account {}", accountId);
        } catch (DataIntegrityViolationException raced) {
            try {
                transactionTemplate.executeWithoutResult(
                        status -> sessionRepository.touchOpenSession(accountId, now));
                diagnosticCounters.recordSessionCreationSuccess();
                log.debug("session tracking succeeded via fallback for account {}", accountId);
            } catch (Exception e) {
                diagnosticCounters.recordSessionCreationFailed();
                log.warn("Session tracking fallback failed for account {}", accountId, e);
            }
        } catch (Exception e) {
            diagnosticCounters.recordSessionCreationFailed();
            log.warn("Session tracking failed for account {}", accountId, e);
        }
    }

    private void upsertSession(UUID accountId, Instant now) {
        Optional<PlayerSession> open =
                sessionRepository.findFirstByAccountIdAndEndedAtIsNullOrderByStartedAtDesc(accountId);
        if (open.isPresent()) {
            PlayerSession session = open.get();
            // A precise client session is owned by the lifecycle service and its
            // timeout scanner; a gameplay request only records fallback activity.
            if (session.getClientSessionId() != null) {
                sessionRepository.touch(session.getId(), now);
                return;
            }
            if (!session.getLastActivityAt().isBefore(now.minus(properties.getSessionTimeout()))) {
                sessionRepository.touch(session.getId(), now);
                return;
            }
            sessionRepository.closeSession(session.getId(), END_REASON_INACTIVITY);
        }

        PlayerSession session = new PlayerSession();
        session.setAccountId(accountId);
        session.setStartedAt(now);
        session.setLastActivityAt(now);
        session.setEstimated(true);
        sessionRepository.saveAndFlush(session);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
