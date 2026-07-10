package com.cenk.valocase.analytics.dto;

/**
 * Payload for start and resume. clientSentAtUtc is diagnostic only; the server
 * clock is authoritative for every stored timestamp.
 */
public record SessionStartRequest(
        String clientSessionId,
        String installationId,
        String appVersion,
        String platform,
        String clientSentAtUtc,
        Long lifecycleSequence) {
}
