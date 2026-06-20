package com.cenk.valocase.daily.dto;

import java.time.Instant;

/**
 * Daily reward status for the guest.
 */
public record DailyStatusResponse(
        boolean claimable,
        long rewardVp,
        Instant lastClaimAt,
        Instant nextClaimAt,
        long secondsUntilNextClaim
) {
}
