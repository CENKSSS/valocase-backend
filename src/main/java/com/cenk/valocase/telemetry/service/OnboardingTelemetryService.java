package com.cenk.valocase.telemetry.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.cenk.valocase.account.service.RegistrationRejectionReason;
import com.cenk.valocase.common.country.CountryCodes;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.telemetry.TelemetryProperties;
import com.cenk.valocase.telemetry.domain.NetworkErrorCategory;
import com.cenk.valocase.telemetry.domain.OnboardingEvent;
import com.cenk.valocase.telemetry.domain.OnboardingEventName;
import com.cenk.valocase.telemetry.dto.OnboardingEventRequest;
import com.cenk.valocase.telemetry.repository.OnboardingEventRepository;
import com.cenk.valocase.analytics.domain.ClientPlatform;

import lombok.extern.slf4j.Slf4j;

/**
 * Validates and stores pre-account onboarding events.
 *
 * <p>Two properties matter more than anything else here. Ingestion is
 * <em>idempotent</em>, keyed on the client's eventId, so a client that retries a
 * timed-out send does not inflate the funnel. And ingestion is
 * <em>inert</em>: this service is never called from registration or startup, and
 * the controller catches everything, so no failure in this class can affect a
 * player's ability to create an account or open the game.
 */
@Service
@Slf4j
public class OnboardingTelemetryService {

    /** Outcome of one ingestion attempt. */
    public enum Result {
        /** Stored. */
        ACCEPTED,
        /** Already stored under this eventId; nothing written. */
        DUPLICATE,
        /** Failed validation. */
        INVALID,
        /** Over the per-installation rate limit. */
        RATE_LIMITED,
        /** Persistence failed for a reason other than a duplicate. */
        ERROR
    }

    private final OnboardingEventRepository repository;
    private final InstallationRateLimiter rateLimiter;
    private final DiagnosticCounters counters;
    private final TelemetryProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OnboardingTelemetryService(OnboardingEventRepository repository,
                                      InstallationRateLimiter rateLimiter,
                                      DiagnosticCounters counters,
                                      TelemetryProperties properties,
                                      PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.counters = counters;
        this.properties = properties;
        // The insert runs in its own transaction, so the duplicate-key exception
        // can be caught and turned into a 202 without leaving a rollback-only
        // transaction behind for a caller to trip over.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Result ingest(OnboardingEventRequest request) {
        if (request == null) {
            counters.recordTelemetryRejected("MISSING_BODY");
            return Result.INVALID;
        }

        Optional<OnboardingEventName> eventName = OnboardingEventName.fromWire(request.eventName());
        if (eventName.isEmpty()) {
            counters.recordTelemetryRejected("UNKNOWN_EVENT");
            log.warn("telemetry rejected: reason=UNKNOWN_EVENT appVersion={} platform={}",
                    safeShort(request.appVersion()), safeShort(request.platform()));
            return Result.INVALID;
        }

        String installationId = boundedIdentifier(request.installationId());
        if (installationId == null) {
            counters.recordTelemetryRejected("BAD_INSTALLATION_ID");
            log.warn("telemetry rejected: reason=BAD_INSTALLATION_ID event={} appVersion={}",
                    eventName.get().wireName(), safeShort(request.appVersion()));
            return Result.INVALID;
        }

        String eventId = boundedIdentifier(request.eventId());
        if (eventId == null) {
            counters.recordTelemetryRejected("BAD_EVENT_ID");
            log.warn("telemetry rejected: reason=BAD_EVENT_ID event={} appVersion={}",
                    eventName.get().wireName(), safeShort(request.appVersion()));
            return Result.INVALID;
        }

        String appVersion = request.appVersion() == null ? null : request.appVersion().trim();
        if (appVersion != null && appVersion.length() > properties.getMaxAppVersionLength()) {
            counters.recordTelemetryRejected("BAD_APP_VERSION");
            return Result.INVALID;
        }

        // ClientPlatform.fromRaw already falls back to UNKNOWN for anything it does
        // not recognise, which keeps a new platform from being a 400.
        String platform = ClientPlatform.fromRaw(request.platform()).name();

        if (carriesCountry(eventName.get())
                && !CountryCodes.isBlank(request.countryCode())
                && !CountryCodes.isValid(request.countryCode())) {
            // Unlike a bad platform this is refused rather than nulled. The
            // country arrives from a fixed picker, so an unrecognised value means
            // the client is sending something other than the code — a localized
            // name, most likely — and quietly dropping it would let that ship.
            counters.recordTelemetryRejected("BAD_COUNTRY_CODE");
            log.warn("telemetry rejected: reason=BAD_COUNTRY_CODE event={} appVersion={}",
                    eventName.get().wireName(), safeShort(request.appVersion()));
            return Result.INVALID;
        }

        Instant now = Instant.now();
        if (!rateLimiter.tryAcquire(installationId, now)) {
            counters.recordTelemetryRateLimited();
            log.warn("telemetry rate limited: event={} appVersion={} platform={} installation={}",
                    eventName.get().wireName(), appVersion, platform, pseudonym(installationId));
            return Result.RATE_LIMITED;
        }

        OnboardingEvent event = new OnboardingEvent();
        event.setEventId(eventId);
        event.setInstallationId(installationId);
        event.setEventName(eventName.get().wireName());
        event.setClientTimestampUtc(parseClientTimestamp(request.clientTimestampUtc()));
        event.setReceivedAt(now);
        event.setAppVersion(appVersion);
        event.setPlatform(platform);
        event.setCountryCode(countryCodeFor(eventName.get(), request.countryCode()));
        event.setRejectionReason(rejectionReasonFor(eventName.get(), request.rejectionReason()));
        event.setNetworkErrorCategory(networkErrorFor(eventName.get(), request.networkErrorCategory()));
        event.setHttpStatus(httpStatusFor(eventName.get(), request.httpStatus()));

        Result result = store(event);
        counters.recordTelemetryEvent(eventName.get().wireName(), result.name());
        log.info("telemetry {}: event={} appVersion={} platform={} installation={}",
                result.name().toLowerCase(java.util.Locale.ROOT),
                event.getEventName(), appVersion, platform, pseudonym(installationId));
        return result;
    }

    /**
     * Inserts the event unless its eventId is already present.
     *
     * <p>The existence check is an optimisation; the unique index is the actual
     * guarantee. Two instances handling the same retry can both pass the check,
     * and the loser gets a duplicate-key error which is exactly as good an
     * outcome — the row exists either way.
     */
    private Result store(OnboardingEvent event) {
        try {
            if (repository.existsByEventId(event.getEventId())) {
                return Result.DUPLICATE;
            }
            transactionTemplate.executeWithoutResult(status -> repository.saveAndFlush(event));
            return Result.ACCEPTED;
        } catch (DataIntegrityViolationException duplicate) {
            return Result.DUPLICATE;
        } catch (Exception e) {
            counters.recordTelemetryIngestionFailed();
            log.warn("telemetry ingestion failed: event={}", event.getEventName(), e);
            return Result.ERROR;
        }
    }

    /** Trimmed identifier, or null when absent or past the configured bound. */
    private String boundedIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > properties.getMaxIdentifierLength()) {
            return null;
        }
        return trimmed;
    }

    /**
     * Device clocks are wrong often enough that an unparseable value is not worth
     * losing the whole event over: the field is stored as null and
     * {@code received_at} carries the authoritative time.
     */
    private Instant parseClientTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Rejection reason, kept only for the event it belongs to. Anything sent
     * alongside another event is dropped rather than stored, so the column cannot
     * accumulate values whose meaning depends on the event that carried them.
     */
    private String rejectionReasonFor(OnboardingEventName eventName, String raw) {
        if (eventName != OnboardingEventName.NICKNAME_REJECTED || raw == null || raw.isBlank()) {
            return null;
        }
        // The client reports the same vocabulary the backend validator uses, so an
        // unrecognised code is dropped rather than stored as free text.
        try {
            return RegistrationRejectionReason
                    .valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).name();
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * The funnel steps on which a {@code countryCode} is accepted and stored.
     *
     * <p>{@code country_selected} is the selection itself. The other three are
     * the conversion steps the country breakdown is actually read for, and
     * carrying the code on the row makes each of them answer "which country" on
     * its own — without depending on the installation's {@code country_selected}
     * event having survived a rate limit or a lost request.
     *
     * <p>Every other step is left out on purpose. The reports still break those
     * down by country, by resolving the installation's selection in the view, so
     * widening the accepted surface would buy nothing and cost strictness.
     */
    private static boolean carriesCountry(OnboardingEventName eventName) {
        return eventName == OnboardingEventName.COUNTRY_SELECTED
                || eventName == OnboardingEventName.NICKNAME_SCREEN_SHOWN
                || eventName == OnboardingEventName.REGISTRATION_ATTEMPTED
                || eventName == OnboardingEventName.REGISTRATION_SUCCEEDED;
    }

    /**
     * The country to store, uppercase, or null when this step does not carry one.
     *
     * <p>A code sent alongside any other event is dropped rather than stored, the
     * same way {@code rejectionReason} is. Anything not on the ISO allowlist has
     * already been refused above, so nothing reaching here can be a country name.
     */
    private String countryCodeFor(OnboardingEventName eventName, String raw) {
        if (!carriesCountry(eventName)) {
            return null;
        }
        return CountryCodes.canonical(raw);
    }

    private String networkErrorFor(OnboardingEventName eventName, String raw) {
        if (eventName != OnboardingEventName.REGISTRATION_FAILED) {
            return null;
        }
        return NetworkErrorCategory.fromWire(raw).map(NetworkErrorCategory::wireName).orElse(null);
    }

    private Integer httpStatusFor(OnboardingEventName eventName, Integer raw) {
        if (eventName != OnboardingEventName.REGISTRATION_FAILED || raw == null) {
            return null;
        }
        // Anything outside the HTTP range is noise, not a status.
        return (raw >= 100 && raw <= 599) ? raw : null;
    }

    /**
     * Short, stable, one-way label for an installationId, so two log lines from
     * the same install can be correlated without the id itself ever appearing.
     * Truncated to 8 hex characters: enough to follow one install through a log,
     * far too little to enumerate the id space back.
     */
    static String pseudonym(String installationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(installationId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }

    /** Bounded echo of an untrusted string for a log line. */
    private static String safeShort(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20);
    }
}
