package com.cenk.valocase.adreward.dto;

import java.util.List;

/**
 * Claim for a completed rewarded ad. {@code adToken} is the per-watch token used
 * for idempotency; for now any unique value is accepted, later it becomes the
 * ad-network verification token. {@code earnSessionId} targets the EARN_VP_2X
 * bonus at the current tap session; {@code inputItemIds}/{@code targetSkinIds}/
 * {@code targetSkinId} identify the UPGRADE_PLUS_5 upgrade context.
 */
public record AdRewardClaimRequest(
        String rewardType,
        String adToken,
        String earnSessionId,
        List<String> inputItemIds,
        List<String> targetSkinIds,
        String targetSkinId
) {
}
