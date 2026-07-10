package com.cenk.valocase.analytics.dto;

/** Payload for a best-effort graceful end. endReason is normalized server-side. */
public record SessionEndRequest(
        String clientSessionId,
        String clientSentAtUtc,
        Long lifecycleSequence,
        String endReason) {
}
