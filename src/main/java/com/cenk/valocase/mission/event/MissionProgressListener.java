package com.cenk.valocase.mission.event;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.cenk.valocase.mission.service.MissionProgressService;

import lombok.RequiredArgsConstructor;

/**
 * Applies mission progress from gameplay events. Uses a plain (synchronous)
 * {@code @EventListener} so it runs inside the publishing transaction — progress
 * is atomic with the gameplay action and never counts a rolled-back action.
 */
@Component
@RequiredArgsConstructor
public class MissionProgressListener {

    private final MissionProgressService missionProgressService;

    @EventListener
    public void onMissionProgress(MissionProgressEvent event) {
        missionProgressService.recordProgress(event.accountId(), event.eventType(), event.quantity());
    }
}
