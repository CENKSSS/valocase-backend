package com.cenk.valocase.adreward.dto;

/**
 * Result of a rewarded-ad claim. {@code status} is OK for a fresh activation,
 * DUPLICATE when the same ad was already claimed, or CLEARED when an active bonus
 * was expired. {@code grantedVp} and {@code newVpBalance} are only meaningful for
 * placements that credit the wallet directly (MARKET_VP_2500); they stay 0 for
 * EARN_VP_2X (which only arms a session bonus) and UPGRADE_PLUS_5.
 */
public record AdRewardClaimResponse(
        String rewardType,
        String status,
        boolean earnVp2xActive,
        boolean upgradePlus5Active,
        boolean isAvailable,
        String unavailableReason,
        long earnVp2xRemainingSeconds,
        long grantedVp,
        long newVpBalance
) {
}
