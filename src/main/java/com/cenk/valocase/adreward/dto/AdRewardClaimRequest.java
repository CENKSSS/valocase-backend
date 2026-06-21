package com.cenk.valocase.adreward.dto;

/**
 * Claim for a completed rewarded ad. {@code adToken} is the per-watch token the
 * client supplies for idempotency; for now any unique value is accepted, later it
 * becomes the ad-network verification token.
 */
public record AdRewardClaimRequest(
        String rewardType,
        String adToken
) {
}
