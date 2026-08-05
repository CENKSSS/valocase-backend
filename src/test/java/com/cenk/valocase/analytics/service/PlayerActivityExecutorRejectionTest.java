package com.cenk.valocase.analytics.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.cenk.valocase.analytics.AnalyticsProperties;
import com.cenk.valocase.analytics.domain.PlayerSession;
import com.cenk.valocase.analytics.repository.PlayerActivityEventRepository;
import com.cenk.valocase.analytics.repository.PlayerSessionRepository;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The tracking executor used to drop work with {@code DiscardPolicy}, in total
 * silence. A dropped task means the account exists but never gets a
 * player_sessions row — invisible in the daily views, with nothing anywhere to
 * say it happened. This asserts it now says so.
 */
class PlayerActivityExecutorRejectionTest {

    /** Queue capacity in PlayerActivityService; one more than this is refused. */
    private static final int QUEUE_CAPACITY = 1000;

    private PlayerSessionRepository sessionRepository;
    private DiagnosticCounters counters;
    private PlayerActivityService service;

    private final CountDownLatch release = new CountDownLatch(1);

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(PlayerSessionRepository.class);
        PlayerActivityEventRepository eventRepository = mock(PlayerActivityEventRepository.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // Hold the single worker thread hostage so the queue can actually fill.
        when(sessionRepository.findFirstByAccountIdAndEndedAtIsNullOrderByStartedAtDesc(any()))
                .thenAnswer(invocation -> {
                    release.await(10, TimeUnit.SECONDS);
                    return Optional.empty();
                });
        when(sessionRepository.saveAndFlush(any(PlayerSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        counters = new DiagnosticCounters();
        service = new PlayerActivityService(
                sessionRepository, eventRepository, transactionManager,
                new AnalyticsProperties(), counters);

        serviceLogger = (Logger) LoggerFactory.getLogger(PlayerActivityService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        serviceLogger.detachAppender(appender);
        service.shutdown();
    }

    @Test
    @Timeout(30)
    void aDiscardedTaskIsWarnedAboutAndCounted() {
        // One task occupies the worker, QUEUE_CAPACITY fill the queue, and
        // everything after that has nowhere to go. Distinct account ids so the
        // per-account write throttle never suppresses a submission.
        int overflow = 20;
        for (int i = 0; i < 1 + QUEUE_CAPACITY + overflow; i++) {
            service.recordActivity(UUID.randomUUID());
        }

        assertTrue(counters.asMap().get("session_task_discarded") > 0,
                "expected discarded tasks to be counted, counters=" + counters.snapshot());

        boolean warned = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .anyMatch(event -> event.getFormattedMessage().contains("session tracking task discarded"));
        assertTrue(warned, "expected a WARN naming the discard, got: "
                + appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    @Timeout(30)
    void submissionIsLogged() {
        service.recordActivity(UUID.randomUUID());

        boolean submitted = appender.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains("session tracking submitted"));
        assertTrue(submitted, "expected the submission to be logged");
    }
}
