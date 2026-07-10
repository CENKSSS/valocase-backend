package com.cenk.valocase.analytics.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cenk.valocase.analytics.AnalyticsProperties;
import com.cenk.valocase.analytics.domain.ClientPlatform;
import com.cenk.valocase.analytics.domain.LifecycleState;
import com.cenk.valocase.analytics.domain.PlayerSession;
import com.cenk.valocase.analytics.domain.PlayerSessionSegment;
import com.cenk.valocase.analytics.domain.SessionEndReason;
import com.cenk.valocase.analytics.dto.SessionAckResponse;
import com.cenk.valocase.analytics.dto.SessionEndRequest;
import com.cenk.valocase.analytics.dto.SessionSignalRequest;
import com.cenk.valocase.analytics.dto.SessionStartRequest;
import com.cenk.valocase.analytics.repository.PlayerSessionRepository;
import com.cenk.valocase.analytics.repository.PlayerSessionSegmentRepository;
import com.cenk.valocase.common.exception.ApiException;

/**
 * Server-authoritative precise session lifecycle. The client reports start,
 * heartbeat, pause, resume and a best-effort end; the server clock stamps every
 * timestamp and computes every duration. One logical session per
 * (account, clientSessionId) and at most one open foreground segment per session
 * are enforced by partial unique indexes; row locks plus a bounded retry on
 * unique collisions keep concurrent and multi-instance requests idempotent.
 */
@Service
public class ClientSessionService {

    static final String SEGMENT_REASON_PAUSE = "PAUSE";
    static final String SEGMENT_REASON_END = "END";
    static final long MAX_SEQUENCE = 1_000_000_000L;
    private static final int APP_VERSION_MAX = 50;
    private static final int MAX_RETRIES = 3;
    private static final String NO_SESSION_STATE = "NONE";

    private final PlayerSessionRepository sessionRepository;
    private final PlayerSessionSegmentRepository segmentRepository;
    private final TransactionTemplate tx;
    private final AnalyticsProperties properties;

    public ClientSessionService(PlayerSessionRepository sessionRepository,
                                PlayerSessionSegmentRepository segmentRepository,
                                PlatformTransactionManager transactionManager,
                                AnalyticsProperties properties) {
        this.sessionRepository = sessionRepository;
        this.segmentRepository = segmentRepository;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public SessionAckResponse start(UUID accountId, SessionStartRequest req) {
        return openOrResume(accountId, req);
    }

    public SessionAckResponse resume(UUID accountId, SessionStartRequest req) {
        return openOrResume(accountId, req);
    }

    private SessionAckResponse openOrResume(UUID accountId, SessionStartRequest req) {
        UUID clientSessionId = parseUuid(req.clientSessionId(), "clientSessionId");
        UUID installationId = parseUuid(req.installationId(), "installationId");
        long seq = validateSequence(req.lifecycleSequence());
        String appVersion = normalizeAppVersion(req.appVersion());
        String platform = ClientPlatform.fromRaw(req.platform()).name();
        return withRetry(() -> tx.execute(status ->
                doOpenOrResume(accountId, clientSessionId, installationId, appVersion, platform, seq)));
    }

    private SessionAckResponse doOpenOrResume(UUID accountId, UUID clientSessionId, UUID installationId,
                                              String appVersion, String platform, long seq) {
        Instant now = Instant.now();
        Optional<PlayerSession> existing =
                sessionRepository.findByAccountIdAndClientSessionId(accountId, clientSessionId);

        if (existing.isPresent()) {
            PlayerSession s = existing.get();
            closeOpenSessionsForAccount(accountId, s.getId(), now);
            if (s.getEndedAt() != null) {
                reopen(s, installationId, appVersion, platform, seq, now);
            } else {
                bumpSequence(s, seq);
                s.setLastActivityAt(latest(s.getLastActivityAt(), now));
                s.setLastHeartbeatAt(now);
                s.setLifecycleState(LifecycleState.FOREGROUND.name());
                if (!segmentRepository.existsBySessionIdAndEndedAtIsNull(s.getId())) {
                    openSegment(s.getId(), now);
                }
                sessionRepository.save(s);
            }
            return ack(s);
        }

        closeOpenSessionsForAccount(accountId, null, now);
        PlayerSession created = createSession(accountId, clientSessionId, installationId, appVersion, platform, seq, now);
        openSegment(created.getId(), now);
        return ack(created);
    }

    public SessionAckResponse heartbeat(UUID accountId, SessionSignalRequest req) {
        UUID clientSessionId = parseUuid(req.clientSessionId(), "clientSessionId");
        long seq = validateSequence(req.lifecycleSequence());
        return tx.execute(status -> {
            PlayerSession s = openSessionOrNull(accountId, clientSessionId);
            if (s == null) {
                return ackNone();
            }
            if (isStale(s, seq)) {
                return ack(s);
            }
            Instant now = Instant.now();
            if (s.getLastHeartbeatAt() != null
                    && Duration.between(s.getLastHeartbeatAt(), now)
                            .compareTo(properties.getHeartbeatWriteThrottle()) < 0) {
                return ack(s);
            }
            s.setLifecycleSequence(seq);
            s.setLastHeartbeatAt(now);
            s.setLastActivityAt(now);
            sessionRepository.save(s);
            return ack(s);
        });
    }

    public SessionAckResponse pause(UUID accountId, SessionSignalRequest req) {
        UUID clientSessionId = parseUuid(req.clientSessionId(), "clientSessionId");
        long seq = validateSequence(req.lifecycleSequence());
        return tx.execute(status -> {
            PlayerSession s = openSessionOrNull(accountId, clientSessionId);
            if (s == null) {
                return ackNone();
            }
            if (isStale(s, seq)) {
                return ack(s);
            }
            Instant now = Instant.now();
            segmentRepository.closeOpenSegment(s.getId(), now, SEGMENT_REASON_PAUSE, false);
            s.setLifecycleState(LifecycleState.PAUSED.name());
            s.setLifecycleSequence(seq);
            s.setLastActivityAt(now);
            sessionRepository.save(s);
            return ack(s);
        });
    }

    public SessionAckResponse end(UUID accountId, SessionEndRequest req) {
        UUID clientSessionId = parseUuid(req.clientSessionId(), "clientSessionId");
        long seq = validateSequence(req.lifecycleSequence());
        SessionEndReason reason = SessionEndReason.fromClient(req.endReason());
        return tx.execute(status -> {
            PlayerSession s = sessionRepository.findByAccountIdAndClientSessionId(accountId, clientSessionId)
                    .orElse(null);
            if (s == null) {
                return ackNone();
            }
            if (s.getEndedAt() != null) {
                return ack(s);
            }
            Instant now = Instant.now();
            closeExplicit(s, reason, now);
            s.setLifecycleSequence(maxSequence(s.getLifecycleSequence(), seq));
            sessionRepository.save(s);
            return ack(s);
        });
    }

    void closeStaleSession(UUID sessionId) {
        tx.executeWithoutResult(status -> {
            PlayerSession s = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
            if (s == null || s.getEndedAt() != null || s.getClientSessionId() == null) {
                return;
            }
            Instant cutoff = Instant.now().minus(properties.getHeartbeatTimeout());
            Instant observed = observedEnd(s);
            if (observed.isAfter(cutoff)) {
                return;
            }
            closeInferred(s, SessionEndReason.INACTIVITY_TIMEOUT);
            sessionRepository.save(s);
        });
    }

    List<UUID> staleOpenClientSessionIds() {
        return sessionRepository.findStaleOpenClientSessionIds(
                Instant.now().minus(properties.getHeartbeatTimeout()));
    }

    private PlayerSession openSessionOrNull(UUID accountId, UUID clientSessionId) {
        PlayerSession s = sessionRepository.findByAccountIdAndClientSessionId(accountId, clientSessionId)
                .orElse(null);
        return (s == null || s.getEndedAt() != null) ? null : s;
    }

    private void closeOpenSessionsForAccount(UUID accountId, UUID exceptId, Instant now) {
        for (PlayerSession open : sessionRepository.lockOpenSessionsForAccount(accountId)) {
            if (exceptId != null && exceptId.equals(open.getId())) {
                continue;
            }
            closeInferred(open, SessionEndReason.REPLACED);
            // Flush the close before any later insert: Hibernate orders inserts
            // ahead of updates in a flush, which would otherwise collide with the
            // one-open-session-per-account partial unique index.
            sessionRepository.saveAndFlush(open);
        }
    }

    private PlayerSession createSession(UUID accountId, UUID clientSessionId, UUID installationId,
                                        String appVersion, String platform, long seq, Instant now) {
        PlayerSession s = new PlayerSession();
        s.setAccountId(accountId);
        s.setClientSessionId(clientSessionId);
        s.setInstallationId(installationId);
        s.setStartedAt(now);
        s.setLastActivityAt(now);
        s.setLastHeartbeatAt(now);
        s.setAppVersion(appVersion);
        s.setPlatform(platform);
        s.setLifecycleSequence(seq);
        s.setLifecycleState(LifecycleState.FOREGROUND.name());
        s.setEstimated(false);
        return sessionRepository.saveAndFlush(s);
    }

    private void reopen(PlayerSession s, UUID installationId, String appVersion,
                        String platform, long seq, Instant now) {
        s.setEndedAt(null);
        s.setDurationSeconds(null);
        s.setEndReason(null);
        s.setExplicitEndedAt(null);
        s.setEstimated(false);
        s.setInstallationId(installationId);
        s.setAppVersion(appVersion);
        s.setPlatform(platform);
        s.setLastActivityAt(now);
        s.setLastHeartbeatAt(now);
        s.setLifecycleState(LifecycleState.FOREGROUND.name());
        bumpSequence(s, seq);
        sessionRepository.saveAndFlush(s);
        openSegment(s.getId(), now);
    }

    private void openSegment(UUID sessionId, Instant now) {
        PlayerSessionSegment segment = new PlayerSessionSegment();
        segment.setSessionId(sessionId);
        segment.setStartedAt(now);
        segment.setEstimated(false);
        segmentRepository.saveAndFlush(segment);
    }

    private void closeExplicit(PlayerSession s, SessionEndReason reason, Instant now) {
        segmentRepository.closeOpenSegment(s.getId(), now, SEGMENT_REASON_END, false);
        s.setEndedAt(now);
        s.setExplicitEndedAt(now);
        s.setEndReason(reason.name());
        s.setEstimated(false);
        s.setLifecycleState(LifecycleState.ENDED.name());
        s.setDurationSeconds(seconds(s.getStartedAt(), now));
    }

    private void closeInferred(PlayerSession s, SessionEndReason reason) {
        Instant observed = observedEnd(s);
        segmentRepository.closeOpenSegment(s.getId(), observed, reason.name(), true);
        s.setEndedAt(observed);
        s.setEndReason(reason.name());
        s.setEstimated(true);
        s.setLifecycleState(LifecycleState.ENDED.name());
        s.setDurationSeconds(seconds(s.getStartedAt(), observed));
    }

    private Instant observedEnd(PlayerSession s) {
        Instant observed = latest(s.getStartedAt(), s.getLastActivityAt());
        return latest(observed, s.getLastHeartbeatAt());
    }

    private void bumpSequence(PlayerSession s, long seq) {
        s.setLifecycleSequence(maxSequence(s.getLifecycleSequence(), seq));
    }

    private boolean isStale(PlayerSession s, long seq) {
        return s.getLifecycleSequence() != null && seq <= s.getLifecycleSequence();
    }

    private SessionAckResponse ack(PlayerSession s) {
        return new SessionAckResponse(s.getId().toString(), s.getLifecycleState(), Instant.now().toString());
    }

    private SessionAckResponse ackNone() {
        return new SessionAckResponse(null, NO_SESSION_STATE, Instant.now().toString());
    }

    private <T> T withRetry(Supplier<T> action) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return action.get();
            } catch (DataIntegrityViolationException ex) {
                last = ex;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "Session update conflicted; retry");
    }

    private static long maxSequence(Long current, long incoming) {
        return current == null ? incoming : Math.max(current, incoming);
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static long seconds(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).getSeconds());
    }

    private UUID parseUuid(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    private long validateSequence(Long seq) {
        if (seq == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lifecycleSequence is required");
        }
        if (seq < 1 || seq > MAX_SEQUENCE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "lifecycleSequence out of range");
        }
        return seq;
    }

    private String normalizeAppVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String trimmed = raw.trim();
        return trimmed.length() > APP_VERSION_MAX ? trimmed.substring(0, APP_VERSION_MAX) : trimmed;
    }
}
