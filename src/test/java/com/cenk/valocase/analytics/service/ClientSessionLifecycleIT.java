package com.cenk.valocase.analytics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cenk.valocase.analytics.dto.SessionAckResponse;
import com.cenk.valocase.analytics.dto.SessionEndRequest;
import com.cenk.valocase.analytics.dto.SessionSignalRequest;
import com.cenk.valocase.analytics.dto.SessionStartRequest;

/**
 * Exercises the precise client session lifecycle against a real PostgreSQL 16,
 * including idempotency, the pause/resume segment model, explicit vs timeout
 * closure, and concurrent starts collapsing to one session.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "valocase.analytics.heartbeat-write-throttle=PT0S",
        "valocase.analytics.heartbeat-timeout=PT2M",
        "valocase.analytics.timeout-scan-interval=PT1H"
})
class ClientSessionLifecycleIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ClientSessionService service;
    @Autowired JdbcTemplate jdbc;

    private UUID accountId;

    @BeforeEach
    void createAccount() {
        accountId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, guest_token, display_name, status, created_at, last_seen_at,
                                      avatar_id, level, current_level_xp, total_xp)
                VALUES (?, ?, 'Tester', 'ACTIVE', now(), now(), 'avatar_1', 1, 0, 0)
                """, accountId, UUID.randomUUID());
    }

    private SessionStartRequest startReq(UUID clientSessionId, long seq) {
        return new SessionStartRequest(clientSessionId.toString(), UUID.randomUUID().toString(),
                "1.0.0", "android", "2026-01-01T00:00:00Z", seq);
    }

    private int openSessions(UUID clientSessionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_sessions WHERE account_id = ? AND client_session_id = ? AND ended_at IS NULL",
                Integer.class, accountId, clientSessionId);
    }

    private int segmentCount(UUID clientSessionId, boolean onlyOpen) {
        String sql = "SELECT COUNT(*) FROM player_session_segments g JOIN player_sessions s ON s.id = g.session_id"
                + " WHERE s.client_session_id = ?" + (onlyOpen ? " AND g.ended_at IS NULL" : "");
        return jdbc.queryForObject(sql, Integer.class, clientSessionId);
    }

    private String state(UUID clientSessionId) {
        return jdbc.queryForObject(
                "SELECT lifecycle_state FROM player_sessions WHERE client_session_id = ?", String.class, clientSessionId);
    }

    @Test
    void startCreatesOneSessionAndOneOpenSegment() {
        UUID cs = UUID.randomUUID();
        SessionAckResponse ack = service.start(accountId, startReq(cs, 1));
        assertNotNull(ack.serverSessionId());
        assertEquals("FOREGROUND", ack.lifecycleState());
        assertEquals(1, openSessions(cs));
        assertEquals(1, segmentCount(cs, true));
    }

    @Test
    void duplicateStartIsIdempotent() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.start(accountId, startReq(cs, 1));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_sessions WHERE client_session_id = ?", Integer.class, cs));
        assertEquals(1, segmentCount(cs, false));
    }

    @Test
    void staleSequenceIsIgnored() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 5));
        service.heartbeat(accountId, new SessionSignalRequest(cs.toString(), null, 3L));
        Long seq = jdbc.queryForObject(
                "SELECT lifecycle_sequence FROM player_sessions WHERE client_session_id = ?", Long.class, cs);
        assertEquals(5L, seq);
    }

    @Test
    void pauseClosesSegmentAndResumeOpensNewOne() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.pause(accountId, new SessionSignalRequest(cs.toString(), null, 2L));
        assertEquals("PAUSED", state(cs));
        assertEquals(0, segmentCount(cs, true));

        service.resume(accountId, startReq(cs, 3));
        assertEquals("FOREGROUND", state(cs));
        assertEquals(1, segmentCount(cs, true));
        assertEquals(2, segmentCount(cs, false));
    }

    @Test
    void staleResumeDoesNotReverseNewerPause() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.pause(accountId, new SessionSignalRequest(cs.toString(), null, 3L));

        SessionAckResponse ack = service.resume(accountId, startReq(cs, 2));

        assertEquals("PAUSED", ack.lifecycleState());
        assertEquals("PAUSED", state(cs));
        assertEquals(0, segmentCount(cs, true));
        assertEquals(1, segmentCount(cs, false));
    }

    @Test
    void newerResumeIsAccepted() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.pause(accountId, new SessionSignalRequest(cs.toString(), null, 3L));

        SessionAckResponse ack = service.resume(accountId, startReq(cs, 4));

        assertEquals("FOREGROUND", ack.lifecycleState());
        assertEquals("FOREGROUND", state(cs));
        assertEquals(1, segmentCount(cs, true));
        assertEquals(2, segmentCount(cs, false));
    }

    @Test
    void duplicateResumeDoesNotCreateDuplicateSegments() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.pause(accountId, new SessionSignalRequest(cs.toString(), null, 2L));
        service.resume(accountId, startReq(cs, 3));

        SessionAckResponse ack = service.resume(accountId, startReq(cs, 3));

        assertEquals("FOREGROUND", ack.lifecycleState());
        assertEquals(1, segmentCount(cs, true));
        assertEquals(2, segmentCount(cs, false));
    }

    @Test
    void explicitEndClosesSessionAndIsNotEstimated() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        service.end(accountId, new SessionEndRequest(cs.toString(), null, 2L, "QUIT"));

        assertEquals("ENDED", state(cs));
        assertEquals(0, segmentCount(cs, true));
        Boolean estimated = jdbc.queryForObject(
                "SELECT is_estimated FROM player_sessions WHERE client_session_id = ?", Boolean.class, cs);
        assertFalse(estimated);
        assertEquals("QUIT", jdbc.queryForObject(
                "SELECT end_reason FROM player_sessions WHERE client_session_id = ?", String.class, cs));

        service.end(accountId, new SessionEndRequest(cs.toString(), null, 3L, "QUIT"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_sessions WHERE client_session_id = ?", Integer.class, cs));
    }

    @Test
    void missingEndIsClosedByTimeoutAndMarkedEstimated() {
        UUID cs = UUID.randomUUID();
        service.start(accountId, startReq(cs, 1));
        UUID sessionId = jdbc.queryForObject(
                "SELECT id FROM player_sessions WHERE client_session_id = ?", UUID.class, cs);

        jdbc.update("""
                UPDATE player_sessions
                SET last_heartbeat_at = now() - interval '10 minutes',
                    last_activity_at = now() - interval '10 minutes'
                WHERE id = ?
                """, sessionId);

        List<UUID> stale = service.staleOpenClientSessionIds();
        assertTrue(stale.contains(sessionId));
        service.closeStaleSession(sessionId);

        assertEquals("ENDED", state(cs));
        assertEquals("INACTIVITY_TIMEOUT", jdbc.queryForObject(
                "SELECT end_reason FROM player_sessions WHERE client_session_id = ?", String.class, cs));
        assertTrue(jdbc.queryForObject(
                "SELECT is_estimated FROM player_sessions WHERE client_session_id = ?", Boolean.class, cs));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_sessions WHERE client_session_id = ? AND explicit_ended_at IS NOT NULL",
                Integer.class, cs));
        assertEquals(0, segmentCount(cs, true));
    }

    @Test
    void concurrentStartsCollapseToOneSession() throws Exception {
        UUID cs = UUID.randomUUID();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        service.start(accountId, startReq(cs, 1));
                    } catch (RuntimeException ignored) {
                        // a losing racer may exhaust retries; the winner still creates the session
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM player_sessions WHERE client_session_id = ?", Integer.class, cs));
        assertEquals(1, segmentCount(cs, true));
    }
}
