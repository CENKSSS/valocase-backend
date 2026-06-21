package com.cenk.valocase.adreward.dto;

/**
 * Result of a rewarded-ad claim. {@code vpGranted}/{@code newVpBalance} are set
 * for EARN_VP_2X; {@code upgradeBuff} is set for UPGRADE_PLUS_5. {@code status}
 * is OK for a fresh grant or DUPLICATE when the same ad was already claimed.
 */
public record AdRewardClaimResponse(
        String rewardType,
        String status,
        long vpGranted,
        Long newVpBalance,
        UpgradeBuffStateResponse upgradeBuff,
        int remainingToday,
        long cooldownRemainingSeconds
) {
}
