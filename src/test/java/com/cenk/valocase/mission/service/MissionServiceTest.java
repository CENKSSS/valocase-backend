package com.cenk.valocase.mission.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.mission.domain.MissionDefinition;
import com.cenk.valocase.mission.domain.MissionStatus;
import com.cenk.valocase.mission.domain.PlayerMission;
import com.cenk.valocase.mission.dto.MissionClaimResponse;
import com.cenk.valocase.mission.repository.MissionDefinitionRepository;
import com.cenk.valocase.mission.repository.PlayerMissionRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

    @Mock private MissionDefinitionRepository missionDefinitionRepository;
    @Mock private PlayerMissionRepository playerMissionRepository;
    @Mock private WalletService walletService;

    @InjectMocks private MissionService service;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID MISSION = UUID.randomUUID();

    private static MissionDefinition def() {
        MissionDefinition d = new MissionDefinition();
        d.setId(MISSION);
        return d;
    }

    private static PlayerMission pm(MissionStatus status, long reward) {
        PlayerMission p = new PlayerMission();
        p.setId(UUID.randomUUID());
        p.setAccountId(ACCOUNT);
        p.setMissionId(MISSION);
        p.setStatus(status);
        p.setRewardVp(reward);
        return p;
    }

    @Test
    void missionNotFound_throws404() {
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, MISSION));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void noProgressRow_throws422() {
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.of(def()));
        when(playerMissionRepository.findForUpdate(eq(ACCOUNT), eq(MISSION), any())).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, MISSION));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void inProgressMission_throws422() {
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.of(def()));
        when(playerMissionRepository.findForUpdate(eq(ACCOUNT), eq(MISSION), any()))
                .thenReturn(Optional.of(pm(MissionStatus.IN_PROGRESS, 500)));
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, MISSION));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void alreadyClaimed_throws409() {
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.of(def()));
        when(playerMissionRepository.findForUpdate(eq(ACCOUNT), eq(MISSION), any()))
                .thenReturn(Optional.of(pm(MissionStatus.CLAIMED, 500)));
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, MISSION));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void completedMission_claimCreditsWalletAndMarksClaimed() {
        PlayerMission completed = pm(MissionStatus.COMPLETED, 500);
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.of(def()));
        when(playerMissionRepository.findForUpdate(eq(ACCOUNT), eq(MISSION), any()))
                .thenReturn(Optional.of(completed));
        Wallet wallet = new Wallet();
        wallet.setVpBalance(10500L);
        when(walletService.credit(eq(ACCOUNT), eq(500L), eq(MissionService.REASON_MISSION_REWARD), eq(completed.getId())))
                .thenReturn(wallet);

        MissionClaimResponse result = service.claim(ACCOUNT, MISSION);

        assertEquals(500L, result.rewardVp());
        assertEquals(10500L, result.newVpBalance());
        assertEquals(MissionStatus.CLAIMED.name(), result.status());
        assertEquals(MissionStatus.CLAIMED, completed.getStatus());
        verify(playerMissionRepository).save(completed);
    }
}
