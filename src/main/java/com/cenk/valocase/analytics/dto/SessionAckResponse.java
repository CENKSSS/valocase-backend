package com.cenk.valocase.analytics.dto;

/**
 * Compact acknowledgement. serverTimeUtc lets the client measure clock skew;
 * lifecycleState is the authoritative post-event state ("NONE" when no session).
 */
public record SessionAckResponse(
        String serverSessionId,
        String lifecycleState,
        String serverTimeUtc) {
}
