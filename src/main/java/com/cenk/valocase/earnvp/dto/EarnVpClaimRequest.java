package com.cenk.valocase.earnvp.dto;

import java.util.List;

/**
 * Client-reported Earn VP session. The reward is never derived from any
 * client-supplied amount; only these signals are accepted. tapOffsetsMs is
 * optional per-tap timing (ms from session start) used for multiplier/decay.
 */
public record EarnVpClaimRequest(
        Integer tapCount,
        Long sessionDurationMs,
        String clientSessionId,
        List<Long> tapOffsetsMs
) {
}
