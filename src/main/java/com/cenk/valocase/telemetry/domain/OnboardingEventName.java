package com.cenk.valocase.telemetry.domain;

import java.util.Optional;

/**
 * The complete set of onboarding events the endpoint will accept.
 *
 * <p>An allowlist rather than a free string, for two reasons. It bounds what an
 * unauthenticated caller can write into the table, and it keeps the funnel from
 * silently growing a step nobody defined. A name outside this list is a 400, not
 * a stored row.
 */
public enum OnboardingEventName {

    /** The app process started and reached backend bootstrap. */
    APP_LAUNCHED("app_launched"),
    /** The fan-made legal notice became visible. */
    FAN_NOTICE_SHOWN("fan_notice_shown"),
    /** The player accepted the legal notice. */
    FAN_NOTICE_ACCEPTED("fan_notice_accepted"),
    /** The country picker became visible. */
    COUNTRY_SCREEN_SHOWN("country_screen_shown"),
    /** The player picked a country. Carries the chosen code, never its name. */
    COUNTRY_SELECTED("country_selected"),
    /** The nickname/avatar screen became visible. */
    NICKNAME_SCREEN_SHOWN("nickname_screen_shown"),
    /** The client's own validator refused the typed nickname. No request was sent. */
    NICKNAME_REJECTED("nickname_rejected"),
    /** The player pressed Continue with a nickname the client accepted. */
    NICKNAME_CONFIRM_CLICKED("nickname_confirm_clicked"),
    /** The client is about to call POST /api/v1/guest. */
    REGISTRATION_ATTEMPTED("registration_attempted"),
    /** The registration call did not yield a usable account. */
    REGISTRATION_FAILED("registration_failed"),
    /** The client holds a token and considers the account created. */
    REGISTRATION_SUCCEEDED("registration_succeeded");

    private final String wireName;

    OnboardingEventName(String wireName) {
        this.wireName = wireName;
    }

    /** The snake_case name as it travels on the wire and is stored. */
    public String wireName() {
        return wireName;
    }

    /** Resolves a wire name, or empty when it is absent or not on the allowlist. */
    public static Optional<OnboardingEventName> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        for (OnboardingEventName candidate : values()) {
            if (candidate.wireName.equalsIgnoreCase(trimmed)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
