package com.cenk.valocase.daily.dto;

import java.time.Instant;

/**
 * Result of claiming a daily reward.
 */
public record DailyClaimResponse(
        long rewardVp,
        long newVpBalance,
        Instant claimedAt,
        Instant nextClaimAt
) {
}
