package com.cenk.valocase.mission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cenk.valocase.mission.domain.MissionDefinition;
import com.cenk.valocase.mission.domain.MissionStatus;
import com.cenk.valocase.mission.domain.PlayerMission;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.repository.MissionDefinitionRepository;
import com.cenk.valocase.mission.repository.PlayerMissionRepository;

@ExtendWith(MockitoExtension.class)
class MissionProgressServiceTest {

    @Mock private MissionDefinitionRepository missionDefinitionRepository;
    @Mock private PlayerMissionRepository playerMissionRepository;

    @InjectMocks private MissionProgressService service;

    private static final UUID ACCOUNT = UUID.randomUUID();

    private static MissionDefinition def(int target, long reward) {
        MissionDefinition d = new MissionDefinition();
        d.setId(UUID.randomUUID());
        d.setEventType(MissionEventTypes.CASE_OPENED);
        d.setTargetCount(target);
        d.setRewardVp(reward);
        return d;
    }

    @Test
    void firstEvent_createsProgressRowInProgress() {
        MissionDefinition d = def(3, 500);
        when(missionDefinitionRepository.findByActiveTrueAndEventType(MissionEventTypes.CASE_OPENED))
                .thenReturn(List.of(d));
        when(playerMissionRepository.findByAccountIdAndMissionIdAndPeriodKey(eq(ACCOUNT), eq(d.getId()), any()))
                .thenReturn(Optional.empty());

        service.recordProgress(ACCOUNT, MissionEventTypes.CASE_OPENED, 1);

        ArgumentCaptor<PlayerMission> captor = ArgumentCaptor.forClass(PlayerMission.class);
        verify(playerMissionRepository).save(captor.capture());
        PlayerMission saved = captor.getValue();
        assertEquals(1, saved.getProgress());
        assertEquals(MissionStatus.IN_PROGRESS, saved.getStatus());
        assertEquals(500L, saved.getRewardVp());
    }

    @Test
    void reachingTarget_marksCompleted() {
        MissionDefinition d = def(3, 500);
        PlayerMission existing = new PlayerMission();
        existing.setAccountId(ACCOUNT);
        existing.setMissionId(d.getId());
        existing.setProgress(2);
        existing.setStatus(MissionStatus.IN_PROGRESS);
        when(missionDefinitionRepository.findByActiveTrueAndEventType(MissionEventTypes.CASE_OPENED))
                .thenReturn(List.of(d));
        when(playerMissionRepository.findByAccountIdAndMissionIdAndPeriodKey(eq(ACCOUNT), eq(d.getId()), any()))
                .thenReturn(Optional.of(existing));

        service.recordProgress(ACCOUNT, MissionEventTypes.CASE_OPENED, 1);

        assertEquals(3, existing.getProgress());
        assertEquals(MissionStatus.COMPLETED, existing.getStatus());
        verify(playerMissionRepository).save(existing);
    }

    @Test
    void completedMission_isNotAdvancedFurther() {
        MissionDefinition d = def(3, 500);
        PlayerMission existing = new PlayerMission();
        existing.setProgress(3);
        existing.setStatus(MissionStatus.COMPLETED);
        when(missionDefinitionRepository.findByActiveTrueAndEventType(MissionEventTypes.CASE_OPENED))
                .thenReturn(List.of(d));
        when(playerMissionRepository.findByAccountIdAndMissionIdAndPeriodKey(eq(ACCOUNT), eq(d.getId()), any()))
                .thenReturn(Optional.of(existing));

        service.recordProgress(ACCOUNT, MissionEventTypes.CASE_OPENED, 1);

        assertEquals(3, existing.getProgress());
        assertEquals(MissionStatus.COMPLETED, existing.getStatus());
        verify(playerMissionRepository, org.mockito.Mockito.never()).save(any());
    }
}
