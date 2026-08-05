package com.cenk.valocase.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cenk.valocase.telemetry.dto.OnboardingEventRequest;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService.Result;

/**
 * Telemetry against a real Flyway-migrated PostgreSQL.
 *
 * <p>What only a real database can prove: that the unique index on
 * {@code event_id} is the thing enforcing idempotency, that the funnel view
 * compiles and counts distinct installations, and that no forbidden value can be
 * found anywhere in the table.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any PostgreSQL and run it.
 */
@SpringBootTest
class OnboardingTelemetryIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired OnboardingTelemetryService service;

    private String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 18);
    }

    private OnboardingEventRequest event(String installationId, String name, String eventId) {
        return new OnboardingEventRequest(installationId, name, eventId,
                "2026-08-03T10:00:00Z", "1.0.19", "ANDROID", null, null, null, null);
    }

    @Test
    void anAcceptedEventIsPersistedWithServerTime() {
        String installation = uniqueId("inst");
        String eventId = uniqueId("evt");

        assertEquals(Result.ACCEPTED, service.ingest(event(installation, "app_launched", eventId)));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM onboarding_events WHERE event_id = ?", eventId);
        assertEquals("app_launched", row.get("event_name"));
        assertEquals(installation, row.get("installation_id"));
        assertEquals("1.0.19", row.get("app_version"));
        assertEquals("ANDROID", row.get("platform"));
        assertNotNull(row.get("received_at"), "server time must always be set");
        assertNull(row.get("rejection_reason"));
        assertNull(row.get("http_status"));
    }

    @Test
    void theUniqueIndexIsWhatMakesIngestionIdempotent() {
        String installation = uniqueId("inst");
        String eventId = uniqueId("evt");

        assertEquals(Result.ACCEPTED, service.ingest(event(installation, "app_launched", eventId)));
        assertEquals(Result.DUPLICATE, service.ingest(event(installation, "app_launched", eventId)));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM onboarding_events WHERE event_id = ?", Integer.class, eventId);
        assertEquals(1, rows);
    }

    @Test
    void aRejectedEventWritesNothing() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_events", Integer.class);

        service.ingest(event(uniqueId("inst"), "not_an_event", uniqueId("evt")));
        service.ingest(event(null, "app_launched", uniqueId("evt")));

        assertEquals(before,
                (int) jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_events", Integer.class));
    }

    @Test
    void theFunnelViewCountsDistinctInstallationsNotEvents() {
        String installation = uniqueId("inst");

        // One install, four rejections. The funnel must report one blocked
        // player, not four — otherwise a player who mistypes looks like a crowd.
        service.ingest(event(installation, "app_launched", uniqueId("evt")));
        for (int i = 0; i < 4; i++) {
            service.ingest(new OnboardingEventRequest(installation, "nickname_rejected",
                    uniqueId("evt"), null, "1.0.19", "ANDROID", null, "WHITESPACE", null, null));
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT app_launched, nickname_rejected FROM admin_onboarding_funnel "
                        + "WHERE day = (now() AT TIME ZONE 'Europe/Istanbul')::date");

        assertTrue(((Number) row.get("app_launched")).intValue() >= 1);
        assertTrue(((Number) row.get("nickname_rejected")).intValue() >= 1);

        Integer distinct = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT installation_id) FROM onboarding_events "
                        + "WHERE event_name = 'nickname_rejected' AND installation_id = ?",
                Integer.class, installation);
        assertEquals(1, distinct);
    }

    @Test
    void theRejectionBreakdownViewReportsReasons() {
        String installation = uniqueId("inst");
        service.ingest(new OnboardingEventRequest(installation, "nickname_rejected",
                uniqueId("evt"), null, "1.0.19", "ANDROID", null, "INVALID_CHARACTER", null, null));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rejection_reason, installations FROM admin_onboarding_rejections "
                        + "WHERE day = (now() AT TIME ZONE 'Europe/Istanbul')::date");

        assertTrue(rows.stream().anyMatch(r -> "INVALID_CHARACTER".equals(r.get("rejection_reason"))));
    }

    @Test
    void noForbiddenValueEverReachesTheTable() {
        // The column list is the guarantee, but this asserts it against the real
        // schema rather than against the entity: a future migration that adds a
        // free-text column would fail here.
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'onboarding_events'", String.class);

        assertEquals(List.of(
                "app_version", "client_timestamp_utc", "country_code", "event_id", "event_name",
                "http_status", "id", "installation_id", "network_error_category", "platform",
                "received_at", "rejection_reason").size(), columns.size(),
                "unexpected column set: " + columns);

        for (String forbidden : new String[]{"nickname", "display_name", "guest_token", "token",
                "ip", "ip_address", "email", "advertising_id", "device_model", "payload", "properties"}) {
            assertTrue(columns.stream().noneMatch(c -> c.equalsIgnoreCase(forbidden)),
                    "forbidden column present: " + forbidden);
        }
    }
}
