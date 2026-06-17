package com.cenk.valocase.mission.dto;

import java.time.Instant;

/**
 * Result of claiming a completed mission. nextResetAt is the server time the
 * 24h cooldown ends, before which the mission cannot be claimed again.
 */
public record MissionClaimResponse(
        long rewardVp,
        long newVpBalance,
        String status,
        Instant nextResetAt
) {
}
