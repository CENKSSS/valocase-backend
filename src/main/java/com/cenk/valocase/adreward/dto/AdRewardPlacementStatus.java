package com.cenk.valocase.adreward.dto;

/**
 * Availability of one ad placement. {@code earnVp2xActive} is meaningful for
 * EARN_VP_2X; {@code upgradePlus5Active} and
 * {@code upgradePlus5AlreadyUsedForCurrentContext} for UPGRADE_PLUS_5.
 * {@code cooldownRemainingSeconds} is always 0 for these placements and kept for
 * backward compatibility.
 */
public record AdRewardPlacementStatus(
        String rewardType,
        boolean isAvailable,
        String unavailableReason,
        boolean earnVp2xActive,
        boolean upgradePlus5Active,
        boolean upgradePlus5AlreadyUsedForCurrentContext,
        long cooldownRemainingSeconds,
        long earnVp2xRemainingSeconds
) {
}
