package com.cenk.valocase.earnvp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpClaim;
import com.cenk.valocase.earnvp.domain.EarnVpSession;
import com.cenk.valocase.earnvp.dto.EarnVpClaimResponse;
import com.cenk.valocase.earnvp.dto.EarnVpSessionResponse;
import com.cenk.valocase.earnvp.repository.EarnVpClaimRepository;
import com.cenk.valocase.earnvp.repository.EarnVpSessionRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class EarnVpServiceTest {

    @Mock private EarnVpClaimRepository earnVpClaimRepository;
    @Mock private EarnVpSessionRepository earnVpSessionRepository;
    @Mock private WalletService walletService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private EarnVpService service;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String SESSION = SESSION_ID.toString();

    @BeforeEach
    void setUp() {
        service = new EarnVpService(earnVpClaimRepository, earnVpSessionRepository,
                walletService, eventPublisher, clock);
    }

    /** A session that started {@code durationMs} before the fixed clock's now. */
    private EarnVpSession stubSession(long durationMs) {
        EarnVpSession session = new EarnVpSession();
        session.setId(SESSION_ID);
        session.setAccountId(ACCOUNT);
        session.setStartedAt(NOW.minusMillis(durationMs));
        when(earnVpSessionRepository.findByIdAndAccountId(SESSION_ID, ACCOUNT)).thenReturn(Optional.of(session));
        return session;
    }

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
        c.setCreatedAt(NOW);
        return c;
    }

    private static List<Long> offsets(long... values) {
        List<Long> list = new ArrayList<>(values.length);
        for (long v : values) {
            list.add(v);
        }
        return list;
    }

    // ---- session start ----------------------------------------------------

    @Test
    void startSession_persistsServerStart_andReturnsState() {
        when(earnVpSessionRepository.saveAndFlush(any(EarnVpSession.class))).thenAnswer(inv -> {
            EarnVpSession s = inv.getArgument(0);
            s.setId(SESSION_ID);
            return s;
        });

        EarnVpSessionResponse response = service.startSession(ACCOUNT);

        assertEquals(SESSION, response.sessionId());
        assertEquals(NOW.toEpochMilli(), response.serverStartEpochMs());
        assertEquals(EarnVpService.MAX_DURATION_MS, response.maxDurationMs());
        assertEquals(EarnVpService.MAX_TAP_RATE_PER_SECOND, response.maxTapRatePerSecond());
        assertEquals(EarnVpService.MAX_TAPS_PER_CLAIM, response.maxTaps());
    }

    // ---- aggregate fallback (no tapOffsetsMs) -----------------------------

    @Test
    void oneAcceptedTap_grants1Vp() {
        stubSession(1_000L);
        stubFreshClaim(10001L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1, SESSION, null);

        assertEquals(1, result.acceptedTapCount());
        assertEquals(1L, result.vpGranted());
        assertEquals("OK", result.message());
    }

    @Test
    void tenAcceptedTaps_grants17Vp() {
        stubSession(2_000L);
        stubFreshClaim(10017L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, SESSION, null);

        assertEquals(10, result.acceptedTapCount());
        assertEquals(17L, result.vpGranted());
    }

    @Test
    void hundredAcceptedTaps_grants318Vp() {
        stubSession(10_000L);
        stubFreshClaim(10318L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 100, SESSION, null);

        assertEquals(100, result.acceptedTapCount());
        assertEquals(318L, result.vpGranted());
    }

    // ---- timed (tapOffsetsMs) ---------------------------------------------

    @Test
    void closeTaps_multiplierClimbs() {
        stubSession(40_000L);
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            offs.add(i * 100L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 20, SESSION, offs);

        assertEquals(20, result.acceptedTapCount());
        assertEquals(36L, result.vpGranted());
    }

    @Test
    void offsetsFewerThanTapCount_cannotGrantExtraTaps() {
        stubSession(10_000L);
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 37, SESSION, offsets(0, 300, 1200, 1500, 9000));

        assertEquals(5, result.acceptedTapCount());
        assertEquals(8L, result.vpGranted());
    }

    @Test
    void timedClaim_respectsMaxTapCap() {
        stubSession(300_000L);
        stubFreshClaim(99999L);
        List<Long> offs = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            offs.add(i * 100L);
        }

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, SESSION, offs);

        assertEquals(2400, result.acceptedTapCount());
        assertEquals(11286L, result.vpGranted());
    }

    // ---- server-authoritative timing & caps -------------------------------

    @Test
    void immediateClaimAfterStart_grantsNoFullReward() {
        stubSession(0L);
        stubFreshClaim(10000L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, SESSION, null);

        assertEquals(0, result.acceptedTapCount());
        assertEquals(0L, result.vpGranted());
    }

    @Test
    void clientCannotExceedServerElapsedTapBudget() {
        stubSession(2_000L);
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, SESSION, null);

        assertEquals(20, result.acceptedTapCount());
    }

    @Test
    void maxDurationSession_capsAt2400Taps() {
        stubSession(9_999_999L);
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, SESSION, null);

        assertEquals(2400, result.acceptedTapCount());
        assertEquals(11358L, result.vpGranted());
    }

    @Test
    void hugeTapCount_isClampedToServerElapsedRate() {
        stubSession(8_500L);
        stubFreshClaim(99999L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 1_000_000, SESSION, null);

        assertEquals(85, result.acceptedTapCount());
        assertEquals(250L, result.vpGranted());
    }

    @Test
    void successfulClaim_creditsWalletExactlyOnce() {
        stubSession(2_000L);
        stubFreshClaim(10017L);

        service.claim(ACCOUNT, 10, SESSION, null);

        verify(walletService, times(1)).credit(eq(ACCOUNT), eq(17L), eq(EarnVpService.REASON_EARN_VP), any());
    }

    @Test
    void active2xWindow_doublesEarnVpClaim() {
        EarnVpSession session = stubSession(2_000L);
        session.setBonus2xExpiresAt(NOW.plusSeconds(60));
        stubFreshClaim(10034L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, SESSION, null);

        assertEquals(34L, result.vpGranted());
        verify(walletService).credit(eq(ACCOUNT), eq(34L), eq(EarnVpService.REASON_EARN_VP), any());
    }

    @Test
    void active2xWindow_isNotConsumedAfterClaim() {
        EarnVpSession session = stubSession(2_000L);
        Instant expiresAt = NOW.plusSeconds(60);
        session.setBonus2xExpiresAt(expiresAt);
        stubFreshClaim(10034L);

        service.claim(ACCOUNT, 10, SESSION, null);

        assertEquals(expiresAt, session.getBonus2xExpiresAt());
        verify(earnVpSessionRepository, never()).save(any(EarnVpSession.class));
    }

    @Test
    void expired2xWindow_doesNotDoubleEarnVpClaim() {
        EarnVpSession session = stubSession(2_000L);
        session.setBonus2xExpiresAt(NOW.minusSeconds(1));
        stubFreshClaim(10017L);

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, SESSION, null);

        assertEquals(17L, result.vpGranted());
        verify(walletService).credit(eq(ACCOUNT), eq(17L), eq(EarnVpService.REASON_EARN_VP), any());
    }

    // ---- guards / idempotency / throttle ----------------------------------

    @Test
    void unknownOrUnstartedSession_throws400_andDoesNotCredit() {
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION)).thenReturn(Optional.empty());
        when(earnVpSessionRepository.findByIdAndAccountId(SESSION_ID, ACCOUNT)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, SESSION, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void duplicateSession_doesNotGrantTwice() {
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION))
                .thenReturn(Optional.of(existingClaim(10, 17L)));
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), 3520L, Instant.now(), null));

        EarnVpClaimResponse result = service.claim(ACCOUNT, 10, SESSION, offsets(0, 100, 200));

        assertEquals("DUPLICATE", result.message());
        assertEquals(17L, result.vpGranted());
        assertEquals(10, result.acceptedTapCount());
        assertEquals(3520L, result.newBalance());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(earnVpClaimRepository, never()).saveAndFlush(any());
    }

    @Test
    void nonPositiveTapCount_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 0, SESSION, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void blankSessionId_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, "  ", null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void claimWithinOneSecondOfLast_throws429() {
        stubSession(8_500L);
        when(earnVpClaimRepository.findByAccountIdAndClientSessionId(ACCOUNT, SESSION)).thenReturn(Optional.empty());
        when(earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(ACCOUNT))
                .thenReturn(Optional.of(existingClaim(10, 17L)));

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(ACCOUNT, 24, SESSION, null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }
}
