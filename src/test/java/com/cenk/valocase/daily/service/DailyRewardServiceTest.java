package com.cenk.valocase.daily.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
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
import com.cenk.valocase.daily.dto.DailyStatusResponse;
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

    private static DailyRewardState state(Instant lastClaimAt) {
        DailyRewardState s = new DailyRewardState();
        s.setAccountId(ACCOUNT);
        s.setLastClaimAt(lastClaimAt);
        return s;
    }

    private void stubClaimWrites(long balance) {
        when(dailyClaimRepository.save(any(DailyClaim.class))).thenAnswer(inv -> {
            DailyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(balance);
        when(walletService.credit(eq(ACCOUNT), anyLong(), any(), any())).thenReturn(wallet);
    }

    @Test
    void firstClaim_grants2000() {
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT)).thenReturn(Optional.empty());
        stubClaimWrites(12000L);

        DailyClaimResponse result = service.claim(ACCOUNT);

        assertEquals(2000L, result.rewardVp());
        assertEquals(12000L, result.newVpBalance());
    }

    @Test
    void claimWithinCooldown_throws409() {
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT))
                .thenReturn(Optional.of(state(Instant.now().minus(Duration.ofHours(1)))));

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(dailyClaimRepository, never()).save(any());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void claimAfterCooldown_grants2000() {
        when(dailyRewardStateRepository.findForUpdate(ACCOUNT))
                .thenReturn(Optional.of(state(Instant.now().minus(Duration.ofHours(25)))));
        stubClaimWrites(99999L);

        DailyClaimResponse result = service.claim(ACCOUNT);

        assertEquals(2000L, result.rewardVp());
    }

    @Test
    void status_neverClaimed_isClaimable() {
        when(dailyRewardStateRepository.findById(ACCOUNT)).thenReturn(Optional.empty());

        DailyStatusResponse status = service.getStatus(ACCOUNT);

        assertEquals(true, status.claimable());
        assertEquals(2000L, status.rewardVp());
    }

    @Test
    void status_withinCooldown_reportsRemaining() {
        when(dailyRewardStateRepository.findById(ACCOUNT))
                .thenReturn(Optional.of(state(Instant.now().minus(Duration.ofHours(1)))));

        DailyStatusResponse status = service.getStatus(ACCOUNT);

        assertEquals(false, status.claimable());
        assertEquals(true, status.secondsUntilNextClaim() > 0);
    }
}
