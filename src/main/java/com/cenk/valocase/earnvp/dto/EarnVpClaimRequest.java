package com.cenk.valocase.earnvp.dto;

import java.util.List;

/**
 * Client-reported Earn VP session. The reward is never derived from any
 * client-supplied amount; duration comes from the server session start, so
 * {@code sessionDurationMs} is accepted for compatibility but ignored.
 * {@code clientSessionId} must be the id returned by session start. tapOffsetsMs
 * is optional per-tap timing (ms from session start) used for multiplier/decay.
 */
public record EarnVpClaimRequest(
        Integer tapCount,
        Long sessionDurationMs,
        String clientSessionId,
        List<Long> tapOffsetsMs
) {
}
