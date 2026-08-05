package com.cenk.valocase.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The country funnel against a real Flyway-migrated PostgreSQL.
 *
 * <p>The question these views exist to answer is "which countries are we losing
 * players in", and the interesting part is that most funnel steps do not carry a
 * country of their own — they inherit the installation's selection inside the
 * view. That resolution is what is exercised here; it cannot be tested anywhere
 * but against the real SQL.
 */
@SpringBootTest
class OnboardingCountryTelemetryIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired OnboardingTelemetryService service;

    private String uniqueId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 18);
    }

    private OnboardingEventRequest event(String installationId, String name, String countryCode) {
        return new OnboardingEventRequest(installationId, name, uniqueId("evt"),
                "2026-08-04T10:00:00Z", "1.0.20", "ANDROID", countryCode, null, null, null);
    }

    private OnboardingEventRequest rejection(String installationId, String reason) {
        return new OnboardingEventRequest(installationId, "nickname_rejected", uniqueId("evt"),
                null, "1.0.20", "ANDROID", null, reason, null, null);
    }

    private static final String TODAY =
            "day = (now() AT TIME ZONE 'Europe/Istanbul')::date";

    @Test
    void aSelectedCountryIsPersistedUppercase() {
        String installation = uniqueId("inst");
        OnboardingEventRequest request = event(installation, "country_selected", "dz");

        assertEquals(Result.ACCEPTED, service.ingest(request));

        String stored = jdbc.queryForObject(
                "SELECT country_code FROM onboarding_events WHERE event_id = ?",
                String.class, request.eventId());
        assertEquals("DZ", stored);
    }

    @Test
    void aCountryNameNeverReachesTheTable() {
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_events", Integer.class);

        service.ingest(event(uniqueId("inst"), "country_selected", "Türkiye"));
        service.ingest(event(uniqueId("inst"), "country_selected", "TUR"));

        assertEquals(before,
                (int) jdbc.queryForObject("SELECT COUNT(*) FROM onboarding_events", Integer.class));
        Integer names = jdbc.queryForObject(
                "SELECT COUNT(*) FROM onboarding_events WHERE country_code IS NOT NULL "
                        + "AND country_code NOT IN (SELECT country_code FROM onboarding_events "
                        + "WHERE country_code ~ '^[A-Z]{2}$')", Integer.class);
        assertEquals(0, names);
    }

    @Test
    void theCountryScreenStepIsRecordedWithoutACountryOfItsOwn() {
        // The screen was shown; nothing has been picked yet. A country attached
        // to it is dropped rather than stored, so the step cannot claim a
        // selection that had not happened.
        String installation = uniqueId("inst");
        OnboardingEventRequest request = event(installation, "country_screen_shown", "TR");

        assertEquals(Result.ACCEPTED, service.ingest(request));

        assertNull(jdbc.queryForObject(
                "SELECT country_code FROM onboarding_events WHERE event_id = ?",
                String.class, request.eventId()));
    }

    @Test
    void theFunnelByCountryCountsDistinctInstallationsPerCountry() {
        String turkish = uniqueId("inst");
        service.ingest(event(turkish, "app_launched", null));
        service.ingest(event(turkish, "country_screen_shown", null));
        service.ingest(event(turkish, "country_selected", "TR"));
        service.ingest(event(turkish, "registration_succeeded", "TR"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT country_selected, registration_succeeded "
                        + "FROM admin_onboarding_funnel_by_country "
                        + "WHERE country_code = 'TR' AND " + TODAY);

        assertTrue(((Number) row.get("country_selected")).intValue() >= 1);
        assertTrue(((Number) row.get("registration_succeeded")).intValue() >= 1);
    }

    @Test
    void aStepThatCarriesNoCountryStillGetsOneFromTheInstallation() {
        // This is the whole point of the installation_country resolution. The
        // nickname screen's own row has no country; the report must still be able
        // to say which country was blocked there.
        String installation = uniqueId("inst");
        service.ingest(event(installation, "country_selected", "PK"));
        service.ingest(rejection(installation, "INVALID_CHARACTER"));

        // The row itself carries nothing.
        Integer bare = jdbc.queryForObject(
                "SELECT COUNT(*) FROM onboarding_events WHERE installation_id = ? "
                        + "AND event_name = 'nickname_rejected' AND country_code IS NULL",
                Integer.class, installation);
        assertEquals(1, bare);

        // The view resolves it anyway.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rejection_reason, installations FROM admin_nickname_rejections_by_country "
                        + "WHERE country_code = 'PK' AND " + TODAY);
        assertTrue(rows.stream().anyMatch(r -> "INVALID_CHARACTER".equals(r.get("rejection_reason"))),
                "a nickname rejection must be attributable to the selected country");
    }

    @Test
    void anInstallationThatQuitBeforeThePickerLandsInUnknown() {
        // Real players, and the earliest drop-off there is. They must be visible
        // rather than dropped from the funnel for having no country.
        String installation = uniqueId("inst");
        service.ingest(event(installation, "app_launched", null));

        Integer launched = jdbc.queryForObject(
                "SELECT app_launched FROM admin_onboarding_funnel_by_country "
                        + "WHERE country_code = 'UNKNOWN' AND " + TODAY, Integer.class);
        assertTrue(launched >= 1);
    }

    @Test
    void theOriginalFunnelViewStillWorksAlongsideTheCountryOne() {
        // V79's view is untouched by this migration and must keep answering the
        // overall funnel question without a country in sight.
        service.ingest(event(uniqueId("inst"), "app_launched", null));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT app_launched, registration_succeeded FROM admin_onboarding_funnel "
                        + "WHERE " + TODAY);
        assertTrue(((Number) row.get("app_launched")).intValue() >= 1);
    }
}
