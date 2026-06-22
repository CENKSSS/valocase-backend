package com.cenk.valocase.adreward.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.adreward.domain.AdRewardClaim;
import com.cenk.valocase.adreward.domain.AdRewardType;
import com.cenk.valocase.adreward.dto.AdRewardClaimRequest;
import com.cenk.valocase.adreward.dto.AdRewardClaimResponse;
import com.cenk.valocase.adreward.dto.AdRewardPlacementStatus;
import com.cenk.valocase.adreward.dto.AdRewardStatusResponse;
import com.cenk.valocase.adreward.repository.AdRewardClaimRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpSession;
import com.cenk.valocase.earnvp.repository.EarnVpSessionRepository;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class AdRewardServiceTest {

    @Mock private AdRewardClaimRepository adRewardClaimRepository;
    @Mock private EarnVpSessionRepository earnVpSessionRepository;
    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private WalletService walletService;

    private static final Instant NOW = Instant.parse("2026-06-21T00:00:00Z");
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final String SESSION = SESSION_ID.toString();

    private AdRewardService service;

    @BeforeEach
    void setUp() {
        service = new AdRewardService(adRewardClaimRepository, earnVpSessionRepository,
                inventoryItemRepository, skinRepository, new AdRewardPolicy(),
                walletService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void earnVp2xClaim_startsThreeMinuteWindow() {
        EarnVpSession session = session(null);
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.EARN_VP_2X, "ad-1")).thenReturn(Optional.empty());
        when(earnVpSessionRepository.findByAccountIdForUpdate(ACCOUNT)).thenReturn(List.of(session));

        AdRewardClaimResponse response = service.claim(ACCOUNT, AdRewardType.EARN_VP_2X,
                new AdRewardClaimRequest("EARN_VP_2X", "ad-1", SESSION, null, null, null));

        assertTrue(response.earnVp2xActive());
        assertFalse(response.isAvailable());
        assertEquals(180L, response.earnVp2xRemainingSeconds());
        assertEquals(NOW.plusSeconds(180), session.getBonus2xExpiresAt());
        ArgumentCaptor<AdRewardClaim> captor = ArgumentCaptor.forClass(AdRewardClaim.class);
        verify(adRewardClaimRepository).saveAndFlush(captor.capture());
        assertNull(captor.getValue().getGrantedVp());
    }

    @Test
    void earnVp2xClaim_rejectsWhileWindowActive() {
        EarnVpSession session = session(NOW.plusSeconds(60));
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.EARN_VP_2X, "ad-2")).thenReturn(Optional.empty());
        when(earnVpSessionRepository.findByAccountIdForUpdate(ACCOUNT)).thenReturn(List.of(session));

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(
                ACCOUNT, AdRewardType.EARN_VP_2X,
                new AdRewardClaimRequest("EARN_VP_2X", "ad-2", SESSION, null, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals(AdRewardService.CODE_EARN_VP_2X_ACTIVE, ex.getCode());
    }

    @Test
    void clearEarnVp2x_endsWindowEarly() {
        EarnVpSession session = session(NOW.plusSeconds(60));
        session.setBonus2xActive(true);
        when(earnVpSessionRepository.findByIdAndAccountIdForUpdate(SESSION_ID, ACCOUNT))
                .thenReturn(Optional.of(session));

        AdRewardClaimResponse response = service.clearEarnVp2x(ACCOUNT, SESSION);

        assertFalse(response.earnVp2xActive());
        assertTrue(response.isAvailable());
        assertEquals(0L, response.earnVp2xRemainingSeconds());
        assertFalse(session.isBonus2xActive());
        assertNull(session.getBonus2xExpiresAt());
        verify(earnVpSessionRepository).save(session);
    }

    @Test
    void status_returnsRemainingSecondsWhileActive() {
        EarnVpSession session = session(NOW.plusSeconds(75));
        when(earnVpSessionRepository.findByIdAndAccountId(SESSION_ID, ACCOUNT)).thenReturn(Optional.of(session));
        when(earnVpSessionRepository
                .findFirstByAccountIdAndBonus2xExpiresAtAfterOrderByBonus2xExpiresAtDesc(eq(ACCOUNT), any()))
                .thenReturn(Optional.of(session));

        AdRewardStatusResponse response = service.getStatus(ACCOUNT, SESSION, List.of(), List.of());
        AdRewardPlacementStatus earnStatus = response.placements().get(0);

        assertTrue(earnStatus.earnVp2xActive());
        assertFalse(earnStatus.isAvailable());
        assertEquals(AdRewardService.UNLIMITED_REMAINING_TODAY, earnStatus.remainingToday());
        assertEquals(75L, earnStatus.earnVp2xRemainingSeconds());
    }

    @Test
    void status_returnsAvailableWhenNoWindowActive() {
        EarnVpSession session = session(null);
        when(earnVpSessionRepository.findByIdAndAccountId(SESSION_ID, ACCOUNT)).thenReturn(Optional.of(session));
        when(earnVpSessionRepository
                .findFirstByAccountIdAndBonus2xExpiresAtAfterOrderByBonus2xExpiresAtDesc(eq(ACCOUNT), any()))
                .thenReturn(Optional.empty());

        AdRewardStatusResponse response = service.getStatus(ACCOUNT, SESSION, List.of(), List.of());
        AdRewardPlacementStatus earnStatus = response.placements().get(0);

        assertFalse(earnStatus.earnVp2xActive());
        assertTrue(earnStatus.isAvailable());
        assertEquals(AdRewardService.UNLIMITED_REMAINING_TODAY, earnStatus.remainingToday());
        assertEquals(0L, earnStatus.earnVp2xRemainingSeconds());
    }

    @Test
    void status_missingEarnSessionId_returnsEarnUnavailableAndUpgradeNormally() {
        when(adRewardClaimRepository.existsByAccountIdAndRewardTypeAndConsumedFalse(
                ACCOUNT, AdRewardType.UPGRADE_PLUS_5)).thenReturn(false);

        AdRewardStatusResponse response = service.getStatus(ACCOUNT, null, List.of(), List.of());
        AdRewardPlacementStatus earnStatus = response.placements().get(0);
        AdRewardPlacementStatus upgradeStatus = response.placements().get(1);

        assertFalse(earnStatus.isAvailable());
        assertEquals(AdRewardService.CODE_NO_EARN_SESSION, earnStatus.unavailableReason());
        assertEquals(AdRewardService.UNLIMITED_REMAINING_TODAY, earnStatus.remainingToday());
        assertFalse(earnStatus.earnVp2xActive());
        assertEquals(0L, earnStatus.earnVp2xRemainingSeconds());
        assertTrue(upgradeStatus.isAvailable());
        assertEquals(AdRewardService.UNLIMITED_REMAINING_TODAY, upgradeStatus.remainingToday());
        assertNull(upgradeStatus.unavailableReason());
        assertFalse(upgradeStatus.upgradePlus5Active());
    }

    @Test
    void marketClaim_grants2500Vp_andReturnsNewBalance() {
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.MARKET_VP_2500, "ad-m1")).thenReturn(Optional.empty());
        Wallet wallet = new Wallet();
        wallet.setVpBalance(12500L);
        when(walletService.credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any()))
                .thenReturn(wallet);

        AdRewardClaimResponse response = service.claim(ACCOUNT, AdRewardType.MARKET_VP_2500,
                new AdRewardClaimRequest("MARKET_VP_2500", "ad-m1", null, null, null, null));

        assertEquals("OK", response.status());
        assertEquals(2500L, response.grantedVp());
        assertEquals(12500L, response.newVpBalance());
        ArgumentCaptor<AdRewardClaim> captor = ArgumentCaptor.forClass(AdRewardClaim.class);
        verify(adRewardClaimRepository).saveAndFlush(captor.capture());
        assertEquals(AdRewardType.MARKET_VP_2500, captor.getValue().getRewardType());
        assertEquals(2500L, captor.getValue().getGrantedVp());
        assertTrue(captor.getValue().isConsumed());
        verify(walletService).credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any());
    }

    @Test
    void marketClaim_duplicateToken_doesNotCreditAgain() {
        AdRewardClaim prior = new AdRewardClaim();
        prior.setAccountId(ACCOUNT);
        prior.setRewardType(AdRewardType.MARKET_VP_2500);
        prior.setAdToken("ad-m1");
        prior.setGrantedVp(2500L);
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.MARKET_VP_2500, "ad-m1")).thenReturn(Optional.of(prior));
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), 12500L, NOW, null));

        AdRewardClaimResponse response = service.claim(ACCOUNT, AdRewardType.MARKET_VP_2500,
                new AdRewardClaimRequest("MARKET_VP_2500", "ad-m1", null, null, null, null));

        assertEquals("DUPLICATE", response.status());
        assertEquals(2500L, response.grantedVp());
        assertEquals(12500L, response.newVpBalance());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(adRewardClaimRepository, never()).saveAndFlush(any());
    }

    private EarnVpSession session(Instant bonusExpiresAt) {
        EarnVpSession session = new EarnVpSession();
        session.setId(SESSION_ID);
        session.setAccountId(ACCOUNT);
        session.setStartedAt(NOW);
        session.setCreatedAt(NOW);
        session.setBonus2xExpiresAt(bonusExpiresAt);
        return session;
    }
}
