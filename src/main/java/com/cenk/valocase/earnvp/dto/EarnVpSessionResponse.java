package com.cenk.valocase.earnvp.dto;

/**
 * Server-issued Earn VP session state. The client taps locally against these
 * server limits, then claims with {@code sessionId}; the backend recomputes
 * everything from its own start time.
 */
public record EarnVpSessionResponse(
        String sessionId,
        long serverStartEpochMs,
        long maxDurationMs,
        int maxTapRatePerSecond,
        int maxTaps
) {
}
