package com.cenk.valocase.mission.dto;

/**
 * A mission with the guest's current progress and status.
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
        String status
) {
}
