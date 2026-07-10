package com.cenk.valocase.analytics.dto;

/** Payload for heartbeat and pause. */
public record SessionSignalRequest(
        String clientSessionId,
        String clientSentAtUtc,
        Long lifecycleSequence) {
}
