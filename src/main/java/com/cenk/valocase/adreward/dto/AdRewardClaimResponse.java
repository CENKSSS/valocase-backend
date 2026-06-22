package com.cenk.valocase.adreward.dto;

/**
 * Result of a rewarded-ad claim. {@code status} is OK for a fresh activation,
 * DUPLICATE when the same ad was already claimed, COOLDOWN when a Market claim is
 * blocked by the cooldown, or CLEARED when an active bonus was expired.
 * {@code grantedVp} and {@code newVpBalance} are only meaningful for placements
 * that credit the wallet directly (MARKET_VP_2500); they stay 0 for EARN_VP_2X
 * (which only arms a session bonus) and UPGRADE_PLUS_5. The {@code market*} fields
 * describe the Market placement's repeat-limit cycle and are 0/false otherwise.
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
        long newVpBalance,
        long marketRemainingClaims,
        boolean marketCooldownActive,
        long marketCooldownRemainingSeconds
) {
}
