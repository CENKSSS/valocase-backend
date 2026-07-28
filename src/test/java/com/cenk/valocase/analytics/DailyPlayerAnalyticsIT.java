package com.cenk.valocase.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the V78 daily roll-up views against a real Postgres migrated by
 * Flyway. Booting this context at all proves the migration's SQL is valid — a
 * broken view would fail Flyway and take the application down on deploy, which
 * no unit test can catch.
 */
@SpringBootTest
@Testcontainers
class DailyPlayerAnalyticsIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    /** 2026-03-10 10:00 Istanbul (UTC+3) — comfortably inside one Istanbul day. */
    private static final OffsetDateTime DAY_START =
            OffsetDateTime.of(2026, 3, 10, 7, 0, 0, 0, ZoneOffset.UTC);

    private UUID insertAccount(String name, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO accounts (id, guest_token, display_name, status, created_at, last_seen_at,
                                      level, current_level_xp, total_xp)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 1, 0, 0)
                """, id, UUID.randomUUID(), name, createdAt, createdAt);
        return id;
    }

    private void insertSession(UUID accountId, OffsetDateTime startedAt, int minutes) {
        jdbc.update("""
                INSERT INTO player_sessions (id, account_id, started_at, last_activity_at, ended_at,
                                             duration_seconds, end_reason, is_estimated)
                VALUES (?, ?, ?, ?, ?, ?, 'INACTIVITY_TIMEOUT', TRUE)
                """,
                UUID.randomUUID(), accountId, startedAt,
                startedAt.plusMinutes(minutes), startedAt.plusMinutes(minutes), minutes * 60L);
    }

    @Test
    void dailyPlayers_reportsOneRowPerPlayerPerDay_withMinutesAndSessionCount() {
        UUID player = insertAccount("DailyTester", DAY_START);
        insertSession(player, DAY_START, 12);
        insertSession(player, DAY_START.plusHours(3), 8);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT username, session_count, estimated_minutes, is_new_user
                FROM admin_daily_players
                WHERE user_id = ? AND day = DATE '2026-03-10'
                """, player);

        assertEquals("DailyTester", row.get("username"));
        assertEquals(2L, ((Number) row.get("session_count")).longValue());
        assertEquals(20.0, ((Number) row.get("estimated_minutes")).doubleValue(), 0.05);
        // The account was created that same day.
        assertTrue((Boolean) row.get("is_new_user"));
    }

    @Test
    void sessionIsAttributedToTheIstanbulDayItStartedOn() {
        // 2026-03-10 22:30 UTC is already 2026-03-11 01:30 in Istanbul.
        UUID player = insertAccount("MidnightTester", DAY_START);
        insertSession(player, OffsetDateTime.of(2026, 3, 10, 22, 30, 0, 0, ZoneOffset.UTC), 15);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT day FROM admin_daily_players WHERE user_id = ?", player);

        assertEquals(1, rows.size());
        assertEquals("2026-03-11", rows.get(0).get("day").toString());
    }

    @Test
    void newPlayerFlagIsFalseOnALaterDay() {
        UUID player = insertAccount("ReturningTester", DAY_START);
        insertSession(player, DAY_START.plusDays(4), 5);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT is_new_user FROM admin_daily_players
                WHERE user_id = ? AND day = DATE '2026-03-14'
                """, player);

        assertFalse((Boolean) row.get("is_new_user"));
    }

    @Test
    void dailySummary_countsDistinctPlayersAndTotalsTheirMinutes() {
        OffsetDateTime day = OffsetDateTime.of(2026, 4, 2, 7, 0, 0, 0, ZoneOffset.UTC);
        UUID first = insertAccount("SummaryA", day);
        UUID second = insertAccount("SummaryB", day.minusDays(30));
        insertSession(first, day, 10);
        insertSession(first, day.plusHours(2), 10);
        insertSession(second, day, 30);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT player_count, new_player_count, session_count, total_minutes, avg_minutes_per_player
                FROM admin_daily_summary WHERE day = DATE '2026-04-02'
                """);

        assertEquals(2L, ((Number) row.get("player_count")).longValue());
        assertEquals(1L, ((Number) row.get("new_player_count")).longValue());
        assertEquals(3L, ((Number) row.get("session_count")).longValue());
        assertEquals(50.0, ((Number) row.get("total_minutes")).doubleValue(), 0.05);
        assertEquals(25.0, ((Number) row.get("avg_minutes_per_player")).doubleValue(), 0.05);
    }

    @Test
    void systemEventAccountNeverAppears() {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_daily_players
                WHERE user_id = '00000000-0000-0000-0000-000000000001'
                """, Integer.class);

        assertEquals(0, count);
    }
}
