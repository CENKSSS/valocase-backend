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

    @Test
    void claim_setsCooldownAroundClaimedAtPlus24h() {
        PlayerMission completed = pm(MissionStatus.COMPLETED, 500);
        when(missionDefinitionRepository.findById(MISSION)).thenReturn(Optional.of(def()));
        when(playerMissionRepository.findForUpdate(eq(ACCOUNT), eq(MISSION), any()))
                .thenReturn(Optional.of(completed));
        Wallet wallet = new Wallet();
        wallet.setVpBalance(10500L);
        when(walletService.credit(eq(ACCOUNT), eq(500L), eq(MissionService.REASON_MISSION_REWARD), eq(completed.getId())))
                .thenReturn(wallet);

        java.time.Instant before = java.time.Instant.now();
        MissionClaimResponse result = service.claim(ACCOUNT, MISSION);
        java.time.Instant after = java.time.Instant.now();

        assertEquals(completed.getNextResetAt(), result.nextResetAt());
        org.junit.jupiter.api.Assertions.assertFalse(
                completed.getNextResetAt().isBefore(completed.getClaimedAt().plus(java.time.Duration.ofHours(24))));
        org.junit.jupiter.api.Assertions.assertFalse(
                completed.getNextResetAt().isBefore(before.plus(java.time.Duration.ofHours(24))));
        org.junit.jupiter.api.Assertions.assertFalse(
                completed.getNextResetAt().isAfter(after.plus(java.time.Duration.ofHours(24))));
    }

    @Test
    void getMissions_claimedWithinCooldown_reportsNextResetAt() {
        MissionDefinition d = def();
        d.setTargetCount(3);
        d.setRewardVp(500);
        PlayerMission claimed = pm(MissionStatus.CLAIMED, 500);
        claimed.setPeriodKey(MissionProgressService.ONE_TIME_PERIOD_KEY);
        claimed.setProgress(3);
        claimed.setNextResetAt(java.time.Instant.now().plusSeconds(3600));
        when(missionDefinitionRepository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(java.util.List.of(d));
        when(playerMissionRepository.findByAccountId(ACCOUNT)).thenReturn(java.util.List.of(claimed));

        var responses = service.getMissions(ACCOUNT);

        assertEquals(1, responses.size());
        assertEquals(MissionStatus.CLAIMED.name(), responses.get(0).status());
        assertEquals(3, responses.get(0).progress());
        assertEquals(claimed.getNextResetAt(), responses.get(0).nextResetAt());
    }

    @Test
    void getMissions_claimedAfterCooldown_reportsResetAndAvailable() {
        MissionDefinition d = def();
        d.setTargetCount(3);
        d.setRewardVp(500);
        PlayerMission claimed = pm(MissionStatus.CLAIMED, 500);
        claimed.setPeriodKey(MissionProgressService.ONE_TIME_PERIOD_KEY);
        claimed.setProgress(3);
        claimed.setNextResetAt(java.time.Instant.now().minusSeconds(1));
        when(missionDefinitionRepository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(java.util.List.of(d));
        when(playerMissionRepository.findByAccountId(ACCOUNT)).thenReturn(java.util.List.of(claimed));

        var responses = service.getMissions(ACCOUNT);

        assertEquals(MissionStatus.IN_PROGRESS.name(), responses.get(0).status());
        assertEquals(0, responses.get(0).progress());
        org.junit.jupiter.api.Assertions.assertNull(responses.get(0).nextResetAt());
    }
}
