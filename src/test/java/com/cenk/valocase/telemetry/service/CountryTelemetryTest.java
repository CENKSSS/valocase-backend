package com.cenk.valocase.telemetry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.telemetry.TelemetryProperties;
import com.cenk.valocase.telemetry.domain.OnboardingEvent;
import com.cenk.valocase.telemetry.domain.OnboardingEventName;
import com.cenk.valocase.telemetry.dto.OnboardingEventRequest;
import com.cenk.valocase.telemetry.repository.OnboardingEventRepository;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService.Result;

/**
 * The country field on onboarding telemetry: which steps may carry one, what
 * happens to one attached to a step that may not, and what is never stored.
 */
class CountryTelemetryTest {

    private DiagnosticCounters counters;
    private OnboardingTelemetryService service;

    private final List<OnboardingEvent> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        OnboardingEventRepository repository = mock(OnboardingEventRepository.class);
        counters = new DiagnosticCounters();
        TelemetryProperties properties = new TelemetryProperties();

        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

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

    /** An event with a country, named so the call sites stay readable. */
    private static OnboardingEventRequest event(String name, String eventId, String countryCode) {
        return new OnboardingEventRequest("install-abc", name, eventId, null,
                "1.0.20", "ANDROID", countryCode, null, null, null);
    }

    private OnboardingEvent last() {
        return stored.get(stored.size() - 1);
    }

    @Test
    void theTwoCountryStepsAreOnTheAllowlist() {
        assertEquals(Result.ACCEPTED, service.ingest(event("country_screen_shown", "e1", null)));
        assertEquals(Result.ACCEPTED, service.ingest(event("country_selected", "e2", "TR")));

        assertEquals("country_screen_shown", stored.get(0).getEventName());
        assertEquals("country_selected", stored.get(1).getEventName());
        assertEquals("TR", stored.get(1).getCountryCode());
    }

    @Test
    void aLowercaseCodeIsStoredUppercase() {
        service.ingest(event("country_selected", "e1", "tr"));
        assertEquals("TR", last().getCountryCode());

        service.ingest(event("country_selected", "e2", "  in "));
        assertEquals("IN", last().getCountryCode());
    }

    @Test
    void everyCountryTheClientOffersIsAccepted() {
        String[] codes = {"TR", "IN", "PK", "DZ", "US", "GB", "DE", "FR", "JP", "KR"};
        for (int i = 0; i < codes.length; i++) {
            assertEquals(Result.ACCEPTED,
                    service.ingest(event("country_selected", "e" + i, codes[i])), codes[i]);
            assertEquals(codes[i], last().getCountryCode());
        }
    }

    @Test
    void anInvalidCountryIsRejectedAndNothingIsStored() {
        String[] bad = {"TUR", "Turkey", "Türkiye", "India", "123", "T1", "ZZ", "XX"};
        for (int i = 0; i < bad.length; i++) {
            assertEquals(Result.INVALID,
                    service.ingest(event("country_selected", "e" + i, bad[i])), bad[i]);
        }

        assertTrue(stored.isEmpty(), "an unusable country must not become a row");
        assertEquals((long) bad.length, counters.telemetryRejectionsByReason().get("BAD_COUNTRY_CODE"));
    }

    @Test
    void aLocalizedCountryNameIsRejectedRatherThanStored() {
        // The one failure mode that would quietly poison every country report:
        // a client sending the label it displays instead of the code behind it.
        assertEquals(Result.INVALID, service.ingest(event("country_selected", "e1", "Türkiye")));
        assertTrue(stored.isEmpty());
    }

    @Test
    void theConversionStepsMayCarryACountry() {
        service.ingest(event("nickname_screen_shown", "e1", "IN"));
        service.ingest(event("registration_attempted", "e2", "IN"));
        service.ingest(event("registration_succeeded", "e3", "IN"));

        for (OnboardingEvent event : stored) {
            assertEquals("IN", event.getCountryCode(), event.getEventName());
        }
    }

    @Test
    void aCountryAttachedToAnUnrelatedStepIsDroppedRatherThanStored() {
        // Same rule as rejectionReason: the column must never hold a value whose
        // meaning depends on which event happened to bring it.
        for (String name : new String[]{"app_launched", "fan_notice_shown", "fan_notice_accepted",
                "country_screen_shown", "nickname_rejected", "nickname_confirm_clicked",
                "registration_failed"}) {
            service.ingest(event(name, "evt-" + name, "TR"));
            assertNull(last().getCountryCode(), name);
        }
    }

    @Test
    void aCountryAttachedToAnUnrelatedStepDoesNotRejectTheEvent() {
        // Dropped, not refused — the step itself is still a funnel step worth
        // recording, and losing it would put a hole in the funnel.
        assertEquals(Result.ACCEPTED, service.ingest(event("app_launched", "e1", "TR")));
        assertEquals(1, stored.size());
    }

    @Test
    void anInvalidCountryOnAStepThatCannotCarryOneIsNotAnError() {
        // Nothing is validated that is not going to be stored, so a stray value
        // on an unrelated event cannot take a funnel step away from us.
        assertEquals(Result.ACCEPTED, service.ingest(event("app_launched", "e1", "Türkiye")));
        assertNull(last().getCountryCode());
    }

    @Test
    void aMissingCountryOnACountryBearingStepIsStillAccepted() {
        // The client may report the step before the pick, and older clients never
        // send one at all. Neither is a reason to lose the event.
        String[] blanks = {null, "", "   "};
        for (int i = 0; i < blanks.length; i++) {
            assertEquals(Result.ACCEPTED,
                    service.ingest(event("registration_attempted", "e" + i, blanks[i])),
                    "[" + blanks[i] + "]");
            assertNull(last().getCountryCode());
        }
    }

    @Test
    void everyAllowlistedEventStillIngestsIncludingTheNewOnes() {
        for (OnboardingEventName name : OnboardingEventName.values()) {
            assertEquals(Result.ACCEPTED,
                    service.ingest(event(name.wireName(), "evt-" + name.name(), null)), name.wireName());
        }
        assertEquals(OnboardingEventName.values().length, stored.size());
    }

    @Test
    void anEventNameThatIsNotOnTheAllowlistIsStillRefused() {
        for (String unknown : new String[]{"country_picked", "country_screen", "country_selected_2"}) {
            assertEquals(Result.INVALID, service.ingest(event(unknown, "e1", "TR")), unknown);
        }
        assertTrue(stored.isEmpty());
    }

    @Test
    void resendingACountryEventUnderTheSameEventIdStaysIdempotent() {
        assertEquals(Result.ACCEPTED, service.ingest(event("country_selected", "same", "TR")));
        assertEquals(Result.DUPLICATE, service.ingest(event("country_selected", "same", "TR")));
        // Even a retry that changed its mind about the country writes nothing.
        assertEquals(Result.DUPLICATE, service.ingest(event("country_selected", "same", "IN")));

        assertEquals(1, stored.size());
        assertEquals("TR", stored.get(0).getCountryCode());
    }

    @Test
    void theCountryIsTheOnlyNewThingAnEventCanCarry() {
        // A country name arriving under some other JSON key must not find a home:
        // unknown fields are dropped by the parser, and the entity has no
        // free-form column for one. Asserted on the entity's own field list.
        service.ingest(event("country_selected", "e1", "TR"));

        List<String> fields = java.util.Arrays.stream(OnboardingEvent.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .toList();
        assertTrue(fields.contains("countryCode"));
        for (String forbidden : new String[]{"countryName", "country", "region", "locale",
                "ipAddress", "nickname", "properties", "payload"}) {
            assertTrue(fields.stream().noneMatch(f -> f.equalsIgnoreCase(forbidden)),
                    "forbidden field present: " + forbidden);
        }
    }
}
