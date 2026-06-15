package com.cenk.valocase.mission.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.mission.domain.MissionDefinition;
import com.cenk.valocase.mission.domain.MissionStatus;
import com.cenk.valocase.mission.domain.PlayerMission;
import com.cenk.valocase.mission.repository.MissionDefinitionRepository;
import com.cenk.valocase.mission.repository.PlayerMissionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Advances mission progress for an account when a gameplay event occurs. Runs in
 * the publishing gameplay transaction (the listener is synchronous), so progress
 * commits and rolls back together with the action that caused it.
 */
@Service
@RequiredArgsConstructor
public class MissionProgressService {

    /** Progress period_key for ONE_TIME missions. */
    public static final String ONE_TIME_PERIOD_KEY = "";

    private final MissionDefinitionRepository missionDefinitionRepository;
    private final PlayerMissionRepository playerMissionRepository;

    @Transactional
    public void recordProgress(UUID accountId, String eventType, int quantity) {
        if (quantity <= 0) {
            return;
        }

        List<MissionDefinition> definitions = missionDefinitionRepository.findByActiveTrueAndEventType(eventType);
        Instant now = Instant.now();

        for (MissionDefinition def : definitions) {
            Optional<PlayerMission> existing = playerMissionRepository
                    .findByAccountIdAndMissionIdAndPeriodKey(accountId, def.getId(), ONE_TIME_PERIOD_KEY);

            if (existing.isEmpty()) {
                PlayerMission pm = new PlayerMission();
                pm.setAccountId(accountId);
                pm.setMissionId(def.getId());
                pm.setPeriodKey(ONE_TIME_PERIOD_KEY);
                pm.setRewardVp(def.getRewardVp());
                pm.setCreatedAt(now);
                applyProgress(pm, quantity, def.getTargetCount(), now);
                playerMissionRepository.save(pm);
                continue;
            }

            PlayerMission pm = existing.get();
            if (pm.getStatus() == MissionStatus.IN_PROGRESS) {
                applyProgress(pm, quantity, def.getTargetCount(), now);
                playerMissionRepository.save(pm);
            }
            // COMPLETED or CLAIMED: progress is frozen.
        }
    }

    private static void applyProgress(PlayerMission pm, int quantity, int target, Instant now) {
        int newProgress = Math.min(pm.getProgress() + quantity, target);
        pm.setProgress(newProgress);
        pm.setUpdatedAt(now);
        if (newProgress >= target) {
            pm.setStatus(MissionStatus.COMPLETED);
            pm.setCompletedAt(now);
        } else {
            pm.setStatus(MissionStatus.IN_PROGRESS);
        }
    }
}
