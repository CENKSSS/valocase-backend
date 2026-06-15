package com.cenk.valocase.daily.dto;

import java.time.LocalDate;

/**
 * Daily reward status for the guest. All dates are UTC.
 */
public record DailyStatusResponse(
        boolean claimable,
        int currentStreak,
        long nextRewardVp,
        LocalDate lastClaimDate,
        LocalDate nextClaimDate,
        long secondsUntilNextClaim
) {
}
