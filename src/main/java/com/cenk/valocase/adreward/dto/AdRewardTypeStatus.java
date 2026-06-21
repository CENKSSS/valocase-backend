package com.cenk.valocase.adreward.dto;

public record AdRewardTypeStatus(
        String rewardType,
        boolean isAvailable,
        int remainingToday,
        int dailyLimit,
        long cooldownRemainingSeconds
) {
}
