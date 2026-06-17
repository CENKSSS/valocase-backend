package com.cenk.valocase.earnvp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpClaim;
import com.cenk.valocase.earnvp.dto.EarnVpClaimResponse;
import com.cenk.valocase.earnvp.repository.EarnVpClaimRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class EarnVpServiceTest {

    @Mock private EarnVpClaimRepository earnVpClaimRepository;
    @Mock private WalletService walletService;

    @InjectMocks private EarnVpService service;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String SESSION = "session-1";

    private void stubFreshClaim(long resultingBalance) {
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION)).thenReturn(Optional.empty());
        when(earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(ACCOUNT)).thenReturn(Optional.empty());
        when(earnVpClaimRepository.saveAndFlush(any(EarnVpClaim.class))).thenAnswer(inv -> {
            EarnVpClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(resultingBalance);
        when(walletService.credit(eq(ACCOUNT), anyLong(), any(), any())).thenReturn(wallet);
    }

    private static EarnVpClaim existingClaim(int acceptedTaps, long vpGranted) {
        EarnVpClaim c = new EarnVpClaim();
        c.setAccountId(ACCOUNT);
        c.setClientSessionId(SESSION);
        c.setTapCountAccepted(acceptedTaps);
        c.setVpGranted(vpGranted);
        c.setCreatedAt(Instant.now());
        return c;
    }

    private static List<Long> offsets(long... values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long v : values) {
            list.add(v);
        }
        return list;
    }

    // ---- aggregate fallback (no tapOffsetsMs) -----------------------------

    @Test
    void oneAcceptedTap_grants1Vp() {
        stubFreshClaim(10001L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1, 1_000L, SESSION, null);

        assertEquals(1, result.acceptedTapCount());
        assertEquals(1L, result.vpGranted());
        assertEquals("OK", result.message());
    }

    @Test
    void tenAcceptedTaps_grants17Vp() {
        stubFreshClaim(10017L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, 2_000L, SESSION, null);

        assertEquals(10, result.acceptedTapCount());
        assertEquals(17L, result.vpGranted());
    }

    @Test
    void hundredAcceptedTaps_grants318Vp() {
        stubFreshClaim(10318L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 100, 10_000L, SESSION, null);

        assertEquals(100, result.acceptedTapCount());
        assertEquals(318L, result.vpGranted());
    }

    @Test
    void emptyOffsets_usesAggregateFallback() {
        stubFreshClaim(10017L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, 2_000L, SESSION, List.of());

        assertEquals(10, result.acceptedTapCount());
        assertEquals(17L, result.vpGranted());
    }

    // ---- timed (tapOffsetsMs) ---------------------------------------------

    @Test
    void closeTaps_multiplierClimbs() {
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            offs.add(i * 100L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 20, 40_000L, SESSION, offs);

        assertEquals(20, result.acceptedTapCount());
        assertEquals(36L, result.vpGranted());
    }

    @Test
    void farApartTaps_decayKeepsMultiplierLow() {
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            offs.add(i * 2_000L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 20, 40_000L, SESSION, offs);

        assertEquals(20, result.acceptedTapCount());
        assertEquals(32L, result.vpGranted());
        assertTrue(result.vpGranted() < 36L);
    }

    @Test
    void longIdleGap_clampsMultiplierTowardOne() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 4, 6_000L, SESSION, offsets(0, 100, 200, 5200));

        assertEquals(4, result.acceptedTapCount());
        assertEquals(6L, result.vpGranted());
    }

    @Test
    void offsetsFewerThanTapCount_cannotGrantExtraTaps() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 37, 10_000L, SESSION, offsets(0, 300, 1200, 1500, 9000));

        assertEquals(5, result.acceptedTapCount());
        assertEquals(8L, result.vpGranted());
    }

    @Test
    void negativeOffsets_areIgnored() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 5, 100_000L, SESSION, offsets(-50, 0, 100, -10, 200));

        assertEquals(3, result.acceptedTapCount());
        assertEquals(4L, result.vpGranted());
    }

    @Test
    void offsetsBeyondDuration_areClamped() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 3, 1_000L, SESSION, offsets(0, 500, 5000));

        assertEquals(3, result.acceptedTapCount());
        assertEquals(4L, result.vpGranted());
    }

    @Test
    void unsortedOffsets_areNormalized() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 3, 100_000L, SESSION, offsets(200, 0, 100));

        assertEquals(3, result.acceptedTapCount());
        assertEquals(4L, result.vpGranted());
    }

    @Test
    void timedClaim_respectsTenPerSecondCap() {
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            offs.add(i * 20L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, 1_000L, SESSION, offs);

        assertEquals(10, result.acceptedTapCount());
        assertEquals(17L, result.vpGranted());
    }

    @Test
    void timedClaim_respectsMaxTapCap() {
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            offs.add(i * 100L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, 300_000L, SESSION, offs);

        assertEquals(2400, result.acceptedTapCount());
        assertEquals(11286L, result.vpGranted());
    }

    // ---- caps / guards / idempotency / throttle ---------------------------

    @Test
    void hugeTapCount_isClampedToTapRateForDuration() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, 8500L, SESSION, null);

        assertEquals(85, result.acceptedTapCount());
        assertEquals(250L, result.vpGranted());
    }

    @Test
    void maxDurationSession_capsAt2400Taps() {
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, 9_999_999L, SESSION, null);

        assertEquals(2400, result.acceptedTapCount());
        assertEquals(11358L, result.vpGranted());
    }

    @Test
    void duplicateSession_doesNotGrantTwice() {
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION))
                .thenReturn(Optional.of(existingClaim(10, 17L)));
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), 3520L, Instant.now(), null));

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, 8500L, SESSION, offsets(0, 100, 200));

        assertEquals("DUPLICATE", result.message());
        assertEquals(17L, result.vpGranted());
        assertEquals(10, result.acceptedTapCount());
        assertEquals(3520L, result.newBalance());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(earnVpClaimRepository, never()).saveAndFlush(any());
    }

    @Test
    void nonPositiveTapCount_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 0, 8500L, SESSION, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void nonPositiveDuration_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, 0L, SESSION, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void blankSessionId_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, 8500L, "  ", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void claimWithinOneSecondOfLast_throws429() {
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION)).thenReturn(Optional.empty());
        when(earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(ACCOUNT))
                .thenReturn(Optional.of(existingClaim(10, 17L)));

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, 8500L, SESSION, null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }
}
