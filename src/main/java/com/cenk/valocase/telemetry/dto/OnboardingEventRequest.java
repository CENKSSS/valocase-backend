package com.cenk.valocase.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Body of a pre-account onboarding telemetry event.
 *
 * <p><strong>Unknown fields are ignored, deliberately.</strong> This is the
 * safer of the two options and the choice is worth stating plainly. A client
 * that is buggy, older, or newer may put a {@code nickname}, a
 * {@code guestToken}, an advertising id or anything else in the body; with
 * {@code ignoreUnknown = true} such a field is discarded by the parser and can
 * never reach the service, the entity, the database or a log line. Rejecting the
 * request instead would fail closed on forward-compatible clients and would tell
 * a prober which field names exist. Nothing is stored that is not a declared
 * component of this record.
 *
 * <p>Every field is a plain {@code String} rather than a parsed type so that a
 * malformed value produces a clean 400 from our own validation, instead of a
 * Jackson deserialisation error that the catch-all handler would turn into a 500.
 *
 * @param installationId     opaque client-generated install id; required
 * @param eventName          must be on the {@code OnboardingEventName} allowlist
 * @param eventId            opaque client-generated id; the idempotency key
 * @param clientTimestampUtc ISO-8601 instant; optional and untrusted
 * @param appVersion         e.g. {@code 1.0.19}; optional
 * @param platform           {@code ANDROID}, {@code IOS}, {@code EDITOR}, ...
 * @param countryCode        ISO-3166-1 alpha-2, on the country-bearing steps
 *                           only; never a country name
 * @param rejectionReason    only for {@code nickname_rejected}
 * @param networkErrorCategory only for {@code registration_failed}
 * @param httpStatus         only for {@code registration_failed}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OnboardingEventRequest(
        String installationId,
        String eventName,
        String eventId,
        String clientTimestampUtc,
        String appVersion,
        String platform,
        String countryCode,
        String rejectionReason,
        String networkErrorCategory,
        Integer httpStatus
) {
}
