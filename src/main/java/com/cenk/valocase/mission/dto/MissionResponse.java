package com.cenk.valocase.mission.dto;

import java.time.Instant;

/**
 * A mission with the guest's current progress and status. nextResetAt is the
 * server time the 24h claim cooldown ends; null when the mission is claimable.
 */
public record MissionResponse(
        String missionId,
        String code,
        String title,
        String description,
        String eventType,
        int targetCount,
        int progress,
        long rewardVp,
        String status,
        Instant nextResetAt
) {
}
