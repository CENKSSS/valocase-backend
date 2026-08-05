package com.cenk.valocase.telemetry.dto;

/**
 * Reply to an accepted telemetry event.
 *
 * <p>Deliberately says nothing about what is stored. A client needs to know only
 * that it may stop retrying, so a duplicate and a fresh insert both answer 202
 * with a different {@code result} — reporting "duplicate" as an error would push
 * clients into retry loops that create the load this endpoint is designed to
 * survive.
 *
 * @param result {@code accepted} or {@code duplicate}
 */
public record OnboardingEventAck(String result) {

    public static final String ACCEPTED = "accepted";
    public static final String DUPLICATE = "duplicate";

    public static OnboardingEventAck accepted() {
        return new OnboardingEventAck(ACCEPTED);
    }

    public static OnboardingEventAck duplicate() {
        return new OnboardingEventAck(DUPLICATE);
    }
}
