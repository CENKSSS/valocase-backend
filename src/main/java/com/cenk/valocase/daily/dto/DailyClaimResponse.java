package com.cenk.valocase.daily.dto;

import java.time.LocalDate;

/**
 * Result of claiming a daily reward.
 */
public record DailyClaimResponse(
        long rewardVp,
        int currentStreak,
        long newVpBalance,
        LocalDate claimDate
) {
}
