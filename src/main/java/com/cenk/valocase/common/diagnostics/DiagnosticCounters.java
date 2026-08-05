package com.cenk.valocase.common.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * Process-local counters for the first-launch funnel.
 *
 * <p>They answer one question the logs alone answer badly: after a batch of
 * installs, how many registrations were attempted, how many became accounts, and
 * how many were refused for which reason. A rejection currently leaves no trace
 * at all, so "nobody installed the game" and "every registration was refused"
 * look identical from outside.
 *
 * <p>Counts are per process and reset on restart — they are a diagnostic aid, not
 * a system of record. The database remains the truth for how many accounts exist.
 * Nothing here is exposed over HTTP; {@link DiagnosticsReporter} prints them.
 *
 * <p>Counter names are snake_case in {@link #asMap()} because that is how they
 * appear in the log line operators read.
 */
@Component
public class DiagnosticCounters {

    private final LongAdder guestRegistrationStarted = new LongAdder();
    private final LongAdder guestRegistrationSuccess = new LongAdder();
    private final LongAdder guestRegistrationRejected = new LongAdder();
    private final LongAdder sessionCreationSuccess = new LongAdder();
    private final LongAdder sessionCreationFailed = new LongAdder();
    private final LongAdder sessionTaskDiscarded = new LongAdder();
    private final LongAdder telemetryAccepted = new LongAdder();
    private final LongAdder telemetryDuplicate = new LongAdder();
    private final LongAdder telemetryRejected = new LongAdder();
    private final LongAdder telemetryRateLimited = new LongAdder();
    private final LongAdder telemetryIngestionFailed = new LongAdder();

    /** Rejections split by reason code, so the dominant cause is visible at a glance. */
    private final ConcurrentHashMap<String, LongAdder> rejectionsByReason = new ConcurrentHashMap<>();

    /** Accepted telemetry split by event name — the onboarding funnel, live. */
    private final ConcurrentHashMap<String, LongAdder> telemetryByEvent = new ConcurrentHashMap<>();

    /** Why telemetry was refused, split by cause. */
    private final ConcurrentHashMap<String, LongAdder> telemetryRejectionsByReason = new ConcurrentHashMap<>();

    /** A registration request reached the service, before any validation. */
    public void recordGuestRegistrationStarted() {
        guestRegistrationStarted.increment();
    }

    /** An account and its wallet were committed. */
    public void recordGuestRegistrationSuccess() {
        guestRegistrationSuccess.increment();
    }

    /**
     * A registration was refused. Counted both in total and per reason.
     *
     * @param reason a {@link com.cenk.valocase.account.service.RegistrationRejectionReason}
     *               name; never the nickname itself
     */
    public void recordGuestRegistrationRejected(String reason) {
        guestRegistrationRejected.increment();
        rejectionsByReason.computeIfAbsent(reason, key -> new LongAdder()).increment();
    }

    /** A player_sessions row was written (or an existing open session touched). */
    public void recordSessionCreationSuccess() {
        sessionCreationSuccess.increment();
    }

    /** Session tracking threw and was given up on. */
    public void recordSessionCreationFailed() {
        sessionCreationFailed.increment();
    }

    /** The tracking executor refused the task, so no session write was ever attempted. */
    public void recordSessionTaskDiscarded() {
        sessionTaskDiscarded.increment();
    }

    /**
     * A telemetry event finished ingestion.
     *
     * @param eventName allowlisted wire name; never free text
     * @param result    {@code ACCEPTED}, {@code DUPLICATE} or {@code ERROR}
     */
    public void recordTelemetryEvent(String eventName, String result) {
        switch (result) {
            case "ACCEPTED" -> {
                telemetryAccepted.increment();
                telemetryByEvent.computeIfAbsent(eventName, key -> new LongAdder()).increment();
            }
            case "DUPLICATE" -> telemetryDuplicate.increment();
            default -> { /* ERROR is counted by recordTelemetryIngestionFailed */ }
        }
    }

    /** A telemetry event failed validation. */
    public void recordTelemetryRejected(String reason) {
        telemetryRejected.increment();
        telemetryRejectionsByReason.computeIfAbsent(reason, key -> new LongAdder()).increment();
    }

    /** A telemetry event was refused by the per-installation rate limit. */
    public void recordTelemetryRateLimited() {
        telemetryRateLimited.increment();
    }

    /** Telemetry passed validation but could not be stored. */
    public void recordTelemetryIngestionFailed() {
        telemetryIngestionFailed.increment();
    }

    /** Accepted telemetry per event name. */
    public Map<String, Long> telemetryByEvent() {
        Map<String, Long> byEvent = new TreeMap<>();
        telemetryByEvent.forEach((event, count) -> byEvent.put(event, count.sum()));
        return byEvent;
    }

    /** Telemetry validation failures per cause. */
    public Map<String, Long> telemetryRejectionsByReason() {
        Map<String, Long> byReason = new TreeMap<>();
        telemetryRejectionsByReason.forEach((reason, count) -> byReason.put(reason, count.sum()));
        return byReason;
    }

    /** Current values under their published names. Insertion-ordered for stable output. */
    public Map<String, Long> asMap() {
        Map<String, Long> values = new LinkedHashMap<>();
        values.put("guest_registration_started", guestRegistrationStarted.sum());
        values.put("guest_registration_success", guestRegistrationSuccess.sum());
        values.put("guest_registration_rejected", guestRegistrationRejected.sum());
        values.put("session_creation_success", sessionCreationSuccess.sum());
        values.put("session_creation_failed", sessionCreationFailed.sum());
        values.put("session_task_discarded", sessionTaskDiscarded.sum());
        values.put("telemetry_accepted", telemetryAccepted.sum());
        values.put("telemetry_duplicate", telemetryDuplicate.sum());
        values.put("telemetry_rejected", telemetryRejected.sum());
        values.put("telemetry_rate_limited", telemetryRateLimited.sum());
        values.put("telemetry_ingestion_failed", telemetryIngestionFailed.sum());
        return values;
    }

    /** Rejection totals per reason code, sorted by name so the line is stable. */
    public Map<String, Long> rejectionsByReason() {
        Map<String, Long> byReason = new TreeMap<>();
        rejectionsByReason.forEach((reason, count) -> byReason.put(reason, count.sum()));
        return byReason;
    }

    /** One-line rendering for the periodic report. */
    public String snapshot() {
        StringBuilder line = new StringBuilder();
        asMap().forEach((name, value) -> {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(name).append('=').append(value);
        });
        Map<String, Long> byReason = rejectionsByReason();
        if (!byReason.isEmpty()) {
            line.append(" rejections=").append(byReason);
        }
        Map<String, Long> funnel = telemetryByEvent();
        if (!funnel.isEmpty()) {
            line.append(" funnel=").append(funnel);
        }
        Map<String, Long> telemetryRejections = telemetryRejectionsByReason();
        if (!telemetryRejections.isEmpty()) {
            line.append(" telemetryRejections=").append(telemetryRejections);
        }
        return line.toString();
    }
}
