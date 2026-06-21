package com.cenk.valocase.adreward.dto;

/**
 * Result of a rewarded-ad claim. {@code status} is OK for a fresh activation,
 * DUPLICATE when the same ad was already claimed, or CLEARED when an active bonus
 * was expired. No VP is granted here: EARN_VP_2X only arms the session bonus that
 * the Earn VP claim later applies.
 */
public record AdRewardClaimResponse(
        String rewardType,
        String status,
        boolean earnVp2xActive,
        boolean upgradePlus5Active,
        boolean isAvailable,
        String unavailableReason
) {
}
