package com.cenk.valocase.daily.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.daily.domain.DailyClaim;
import com.cenk.valocase.daily.domain.DailyRewardState;
import com.cenk.valocase.daily.dto.DailyClaimResponse;
import com.cenk.valocase.daily.repository.DailyClaimRepository;
import com.cenk.valocase.daily.repository.DailyRewardStateRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class DailyRewardServiceTest {

    @Mock private DailyRewardStateRepository dailyRewardStateRepository;
    @Mock private DailyClaimRepository dailyClaimRepository;
    @Mock private WalletService walletService;

    @InjectMocks private DailyRewardService service;

    private static final UUID ACCOUNT = UUID.randomUUID();

    private static DailyRewardState state(LocalDate lastClaim, int streak) {
        DailyRewardState s = new DailyRewardState();
        s.setAccountId(ACCOUNT);
        s.setLastClaimDate(lastClaim);
        s.setCurrentStreak(streak);
        return s;
    }

    private void stubClaimWrites(long balance) {
        when(dailyClaimRepository.saveAndFlush(any(DailyClaim.class))).thenAnswer(inv -> {
            DailyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(balance);
        when(walletService.credit(eq(ACCOUNT), anyLong(), any(), any())).thenReturn(wallet);
    }

    @Test
    void firstClaim_grants100AndStreak1() {
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        stubClaimWrites(10100L);

        DailyClaimResponse result = service.claim(ACCOUNT);

        assertEquals(100L, result.rewardVp());
        assertEquals(1, result.currentStreak());
        assertEquals(10100L, result.newVpBalance());
    }

    @Test
    void duplicateClaimSameDay_throws409() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT)).thenReturn(Optional.of(state(today, 3)));

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(dailyClaimRepository, never()).saveAndFlush(any());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void consecutiveDay_incrementsStreak() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT)).thenReturn(Optional.of(state(yesterday, 3)));
        stubClaimWrites(99999L);

        DailyClaimResponse result = service.claim(ACCOUNT);

        // streak 3 -> 4, day-4 reward is 300.
        assertEquals(4, result.currentStreak());
        assertEquals(300L, result.rewardVp());
    }

    @Test
    void gap_resetsStreakToOne() {
        LocalDate threeDaysAgo = LocalDate.now(ZoneOffset.UTC).minusDays(3);
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT)).thenReturn(Optional.of(state(threeDaysAgo, 5)));
        stubClaimWrites(99999L);

        DailyClaimResponse result = service.claim(ACCOUNT);

        assertEquals(1, result.currentStreak());
        assertEquals(100L, result.rewardVp());
    }
}
