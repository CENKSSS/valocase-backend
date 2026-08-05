package com.cenk.valocase.common.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.cenk.valocase.account.service.RegistrationRejectionReason;

class DiagnosticCountersTest {

    @Test
    void publishesTheAgreedCounterNames() {
        // These names are the contract with whoever reads the production log.
        assertEquals(
                java.util.List.of(
                        "guest_registration_started",
                        "guest_registration_success",
                        "guest_registration_rejected",
                        "session_creation_success",
                        "session_creation_failed",
                        "session_task_discarded",
                        "telemetry_accepted",
                        "telemetry_duplicate",
                        "telemetry_rejected",
                        "telemetry_rate_limited",
                        "telemetry_ingestion_failed"),
                java.util.List.copyOf(new DiagnosticCounters().asMap().keySet()));
    }

    @Test
    void countsEachOutcomeIndependently() {
        DiagnosticCounters counters = new DiagnosticCounters();

        counters.recordGuestRegistrationStarted();
        counters.recordGuestRegistrationStarted();
        counters.recordGuestRegistrationSuccess();
        counters.recordGuestRegistrationRejected(RegistrationRejectionReason.INVALID_CHARACTER.name());
        counters.recordSessionCreationSuccess();
        counters.recordSessionCreationFailed();
        counters.recordSessionTaskDiscarded();

        Map<String, Long> values = counters.asMap();
        assertEquals(2L, values.get("guest_registration_started"));
        assertEquals(1L, values.get("guest_registration_success"));
        assertEquals(1L, values.get("guest_registration_rejected"));
        assertEquals(1L, values.get("session_creation_success"));
        assertEquals(1L, values.get("session_creation_failed"));
        assertEquals(1L, values.get("session_task_discarded"));
    }

    @Test
    void breaksRejectionsDownByReason() {
        DiagnosticCounters counters = new DiagnosticCounters();

        counters.recordGuestRegistrationRejected(RegistrationRejectionReason.INVALID_CHARACTER.name());
        counters.recordGuestRegistrationRejected(RegistrationRejectionReason.INVALID_CHARACTER.name());
        counters.recordGuestRegistrationRejected(RegistrationRejectionReason.BLANK.name());

        assertEquals(3L, counters.asMap().get("guest_registration_rejected"));
        assertEquals(Map.of("INVALID_CHARACTER", 2L, "BLANK", 1L), counters.rejectionsByReason());
    }

    @Test
    void snapshotIsReadableAndCarriesEveryCounter() {
        DiagnosticCounters counters = new DiagnosticCounters();
        counters.recordGuestRegistrationStarted();
        counters.recordGuestRegistrationRejected(RegistrationRejectionReason.TOO_SHORT.name());

        String snapshot = counters.snapshot();

        assertTrue(snapshot.contains("guest_registration_started=1"), snapshot);
        assertTrue(snapshot.contains("guest_registration_success=0"), snapshot);
        assertTrue(snapshot.contains("session_task_discarded=0"), snapshot);
        assertTrue(snapshot.contains("TOO_SHORT=1"), snapshot);
    }

    @Test
    void snapshotOmitsTheBreakdownWhenNothingWasRejected() {
        assertTrue(!new DiagnosticCounters().snapshot().contains("rejections="));
    }
}
