package com.cenk.valocase.mission.dto;

/**
 * Result of claiming a completed mission.
 */
public record MissionClaimResponse(
        long rewardVp,
        long newVpBalance,
        String status
) {
}
