package com.cenk.valocase.telemetry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.telemetry.domain.OnboardingEvent;
import com.cenk.valocase.telemetry.domain.OnboardingEventName;
import com.cenk.valocase.telemetry.dto.OnboardingEventRequest;
import com.cenk.valocase.telemetry.repository.OnboardingEventRepository;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService.Result;
import com.cenk.valocase.telemetry.TelemetryProperties;

class OnboardingTelemetryServiceTest {

    private OnboardingEventRepository repository;
    private DiagnosticCounters counters;
    private TelemetryProperties properties;
    private OnboardingTelemetryService service;

    private final List<OnboardingEvent> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(OnboardingEventRepository.class);
        counters = new DiagnosticCounters();
        properties = new TelemetryProperties();

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        // A tiny in-memory stand-in for the unique index, so idempotency is
        // exercised rather than assumed.
        when(repository.existsByEventId(anyString())).thenAnswer(inv ->
                stored.stream().anyMatch(e -> e.getEventId().equals(inv.getArgument(0))));
        when(repository.saveAndFlush(any(OnboardingEvent.class))).thenAnswer(inv -> {
            OnboardingEvent event = inv.getArgument(0);
            if (stored.stream().anyMatch(e -> e.getEventId().equals(event.getEventId()))) {
                throw new DataIntegrityViolationException("duplicate key event_id");
            }
            stored.add(event);
            return event;
        });

        service = new OnboardingTelemetryService(
                repository, new InstallationRateLimiter(properties), counters, properties, txManager);
    }

    private static OnboardingEventRequest request(String eventName, String eventId) {
        return new OnboardingEventRequest(
                "install-abc", eventName, eventId, "2026-08-03T10:00:00Z",
                "1.0.19", "ANDROID", null, null, null, null);
    }

    @Test
    void everyAllowlistedEventIsAccepted() {
        for (OnboardingEventName name : OnboardingEventName.values()) {
            Result result = service.ingest(request(name.wireName(), "evt-" + name.name()));
            assertEquals(Result.ACCEPTED, result, name.wireName());
        }
        assertEquals(OnboardingEventName.values().length, stored.size());
        assertEquals((long) OnboardingEventName.values().length,
                counters.asMap().get("telemetry_accepted"));
    }

    @Test
    void unknownEventNameIsRejectedAndNothingIsStored() {
        for (String unknown : new String[]{"app_opened", "nickname_typed", "", "APP_LAUNCHED_"}) {
            assertEquals(Result.INVALID, service.ingest(request(unknown, "evt-1")), unknown);
        }
        assertTrue(stored.isEmpty());
        assertEquals(4L, counters.telemetryRejectionsByReason().get("UNKNOWN_EVENT"));
    }

    @Test
    void nullEventNameIsRejected() {
        assertEquals(Result.INVALID, service.ingest(request(null, "evt-1")));
        assertTrue(stored.isEmpty());
    }

    @Test
    void malformedInstallationIdIsRejected() {
        String tooLong = "x".repeat(properties.getMaxIdentifierLength() + 1);
        for (String bad : new String[]{null, "", "   ", tooLong}) {
            OnboardingEventRequest req = new OnboardingEventRequest(
                    bad, "app_launched", "evt-1", null, "1.0.19", "ANDROID", null, null, null, null);
            assertEquals(Result.INVALID, service.ingest(req));
        }
        assertTrue(stored.isEmpty());
        assertEquals(4L, counters.telemetryRejectionsByReason().get("BAD_INSTALLATION_ID"));
    }

    @Test
    void malformedEventIdIsRejected() {
        String tooLong = "x".repeat(properties.getMaxIdentifierLength() + 1);
        for (String bad : new String[]{null, "", tooLong}) {
            assertEquals(Result.INVALID, service.ingest(request("app_launched", bad)));
        }
        assertTrue(stored.isEmpty());
    }

    @Test
    void oversizedAppVersionIsRejected() {
        OnboardingEventRequest req = new OnboardingEventRequest(
                "install-abc", "app_launched", "evt-1", null,
                "v".repeat(properties.getMaxAppVersionLength() + 1), "ANDROID", null, null, null, null);
        assertEquals(Result.INVALID, service.ingest(req));
        assertTrue(stored.isEmpty());
    }

    @Test
    void resendingTheSameEventIdIsIdempotent() {
        assertEquals(Result.ACCEPTED, service.ingest(request("app_launched", "evt-same")));
        assertEquals(Result.DUPLICATE, service.ingest(request("app_launched", "evt-same")));
        assertEquals(Result.DUPLICATE, service.ingest(request("app_launched", "evt-same")));

        assertEquals(1, stored.size());
        assertEquals(1L, counters.asMap().get("telemetry_accepted"));
        assertEquals(2L, counters.asMap().get("telemetry_duplicate"));
    }

    @Test
    void aRacingDuplicateThatSlipsPastTheCheckIsStillIdempotent() {
        // Simulates two instances passing existsByEventId at once: the loser gets
        // the unique-index violation and must report duplicate, not an error.
        when(repository.existsByEventId(anyString())).thenReturn(false);

        assertEquals(Result.ACCEPTED, service.ingest(request("app_launched", "evt-race")));
        assertEquals(Result.DUPLICATE, service.ingest(request("app_launched", "evt-race")));
        assertEquals(1, stored.size());
    }

    @Test
    void rateLimitingKicksInPerInstallation() {
        properties.setRateLimitEvents(5);
        properties.setRateLimitWindow(Duration.ofMinutes(1));

        int limited = 0;
        for (int i = 0; i < 12; i++) {
            if (service.ingest(request("app_launched", "evt-" + i)) == Result.RATE_LIMITED) {
                limited++;
            }
        }

        assertEquals(7, limited);
        assertEquals(5, stored.size());
        assertEquals(7L, counters.asMap().get("telemetry_rate_limited"));
    }

    @Test
    void oneInstallationCannotRateLimitAnother() {
        properties.setRateLimitEvents(2);

        for (int i = 0; i < 5; i++) {
            service.ingest(request("app_launched", "noisy-" + i));
        }
        OnboardingEventRequest other = new OnboardingEventRequest(
                "install-quiet", "app_launched", "quiet-1", null, "1.0.19", "ANDROID", null, null, null, null);

        assertEquals(Result.ACCEPTED, service.ingest(other));
    }

    @Test
    void rejectionReasonIsKeptOnlyForNicknameRejected() {
        service.ingest(new OnboardingEventRequest("i", "nickname_rejected", "e1", null,
                "1.0.19", "ANDROID", null, "INVALID_CHARACTER", null, null));
        service.ingest(new OnboardingEventRequest("i", "app_launched", "e2", null,
                "1.0.19", "ANDROID", null, "INVALID_CHARACTER", null, null));

        assertEquals("INVALID_CHARACTER", stored.get(0).getRejectionReason());
        assertNull(stored.get(1).getRejectionReason(),
                "a reason attached to an unrelated event must be dropped");
    }

    @Test
    void unknownRejectionReasonIsDroppedRatherThanStoredAsFreeText() {
        service.ingest(new OnboardingEventRequest("i", "nickname_rejected", "e1", null,
                "1.0.19", "ANDROID", null, "<script>alert(1)</script>", null, null));
        assertNull(stored.get(0).getRejectionReason());
    }

    @Test
    void networkDetailIsKeptOnlyForRegistrationFailed() {
        service.ingest(new OnboardingEventRequest("i", "registration_failed", "e1", null,
                "1.0.19", "ANDROID", null, null, "timeout", 500));
        service.ingest(new OnboardingEventRequest("i", "registration_succeeded", "e2", null,
                "1.0.19", "ANDROID", null, null, "timeout", 500));

        assertEquals("timeout", stored.get(0).getNetworkErrorCategory());
        assertEquals(500, stored.get(0).getHttpStatus());
        assertNull(stored.get(1).getNetworkErrorCategory());
        assertNull(stored.get(1).getHttpStatus());
    }

    @Test
    void nonsenseHttpStatusIsDropped() {
        service.ingest(new OnboardingEventRequest("i", "registration_failed", "e1", null,
                "1.0.19", "ANDROID", null, null, "http_error", 99_999));
        assertNull(stored.get(0).getHttpStatus());
    }

    @Test
    void anUnparseableClientClockDoesNotCostUsTheEvent() {
        service.ingest(new OnboardingEventRequest("i", "app_launched", "e1", "not-a-timestamp",
                "1.0.19", "ANDROID", null, null, null, null));

        assertEquals(1, stored.size());
        assertNull(stored.get(0).getClientTimestampUtc());
        // received_at is the authoritative time and is always set.
        assertNotEquals(null, stored.get(0).getReceivedAt());
    }

    @Test
    void unrecognisedPlatformBecomesUnknownRatherThanA400() {
        service.ingest(new OnboardingEventRequest("i", "app_launched", "e1", null,
                "1.0.19", "SomeFuturePhone", null, null, null, null));
        assertEquals(1, stored.size());
        assertEquals("UNKNOWN", stored.get(0).getPlatform());
    }

    @Test
    void nothingSensitiveIsEverPersisted() {
        service.ingest(request("registration_succeeded", "evt-1"));

        ArgumentCaptor<OnboardingEvent> captor = ArgumentCaptor.forClass(OnboardingEvent.class);
        verify(repository).saveAndFlush(captor.capture());
        OnboardingEvent event = captor.getValue();

        // The entity has no field for a nickname or a token, and this pins that:
        // the only free-ish values are the two opaque client ids.
        String everything = String.valueOf(event.getEventId())
                + event.getInstallationId() + event.getEventName()
                + event.getAppVersion() + event.getPlatform() + event.getCountryCode()
                + event.getRejectionReason() + event.getNetworkErrorCategory();
        assertFalse(everything.toLowerCase(java.util.Locale.ROOT).contains("token"));
        assertFalse(everything.toLowerCase(java.util.Locale.ROOT).contains("nickname"));
    }

    @Test
    void aStorageFailureIsReportedAsErrorAndCounted() {
        when(repository.saveAndFlush(any(OnboardingEvent.class)))
                .thenThrow(new IllegalStateException("connection pool exhausted"));

        assertEquals(Result.ERROR, service.ingest(request("app_launched", "evt-1")));
        assertEquals(1L, counters.asMap().get("telemetry_ingestion_failed"));
    }

    @Test
    void aNullBodyIsRejectedWithoutTouchingTheRepository() {
        assertEquals(Result.INVALID, service.ingest(null));
        verify(repository, never()).saveAndFlush(any(OnboardingEvent.class));
    }

    @Test
    void theInstallationPseudonymIsStableShortAndNotTheIdItself() {
        String id = "install-abcdefghijklmnop";
        String first = OnboardingTelemetryService.pseudonym(id);
        String second = OnboardingTelemetryService.pseudonym(id);

        assertEquals(first, second, "must be stable so one install can be followed");
        assertEquals(8, first.length());
        assertFalse(id.contains(first), "the pseudonym must not be a substring of the id");
        assertNotEquals(OnboardingTelemetryService.pseudonym("install-different"), first);
    }
}
