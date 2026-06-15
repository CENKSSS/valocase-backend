package com.cenk.valocase.mission.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.mission.domain.MissionDefinition;
import com.cenk.valocase.mission.domain.MissionStatus;
import com.cenk.valocase.mission.domain.PlayerMission;
import com.cenk.valocase.mission.dto.MissionClaimResponse;
import com.cenk.valocase.mission.dto.MissionResponse;
import com.cenk.valocase.mission.repository.MissionDefinitionRepository;
import com.cenk.valocase.mission.repository.PlayerMissionRepository;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Lists missions with the guest's progress and claims completed missions. A
 * claim and its wallet credit commit together; only COMPLETED -> CLAIMED is
 * allowed, guarded by a locked row, so a reward is granted at most once.
 */
@Service
@RequiredArgsConstructor
public class MissionService {

    public static final String REASON_MISSION_REWARD = "MISSION_REWARD";

    private final MissionDefinitionRepository missionDefinitionRepository;
    private final PlayerMissionRepository playerMissionRepository;
    private final WalletService walletService;

    @Transactional(readOnly = true)
    public List<MissionResponse> getMissions(UUID accountId) {
        List<MissionDefinition> definitions = missionDefinitionRepository.findByActiveTrueOrderBySortOrderAsc();

        Map<UUID, PlayerMission> progressByMission = playerMissionRepository.findByAccountId(accountId).stream()
                .filter(pm -> MissionProgressService.ONE_TIME_PERIOD_KEY.equals(pm.getPeriodKey()))
                .collect(Collectors.toMap(PlayerMission::getMissionId, Function.identity(), (a, b) -> a));

        return definitions.stream().map(def -> {
            PlayerMission pm = progressByMission.get(def.getId());
            int progress = pm != null ? pm.getProgress() : 0;
            MissionStatus status = pm != null ? pm.getStatus() : MissionStatus.IN_PROGRESS;
            return new MissionResponse(
                    def.getId().toString(),
                    def.getCode(),
                    def.getTitle(),
                    def.getDescription(),
                    def.getEventType(),
                    def.getTargetCount(),
                    progress,
                    def.getRewardVp(),
                    status.name()
            );
        }).toList();
    }

    @Transactional
    public MissionClaimResponse claim(UUID accountId, UUID missionId) {
        missionDefinitionRepository.findById(missionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Mission not found: " + missionId));

        PlayerMission pm = playerMissionRepository
                .findForUpdate(accountId, missionId, MissionProgressService.ONE_TIME_PERIOD_KEY)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Mission is not completed"));

        if (pm.getStatus() == MissionStatus.CLAIMED) {
            throw new ApiException(HttpStatus.CONFLICT, "Mission reward already claimed");
        }
        if (pm.getStatus() != MissionStatus.COMPLETED) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Mission is not completed");
        }

        long newVpBalance = walletService
                .credit(accountId, pm.getRewardVp(), REASON_MISSION_REWARD, pm.getId())
                .getVpBalance();

        pm.setStatus(MissionStatus.CLAIMED);
        pm.setClaimedAt(Instant.now());
        pm.setUpdatedAt(Instant.now());
        playerMissionRepository.save(pm);

        return new MissionClaimResponse(pm.getRewardVp(), newVpBalance, MissionStatus.CLAIMED.name());
    }
}
