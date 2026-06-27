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
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpSession;
import com.cenk.valocase.earnvp.repository.EarnVpSessionRepository;
import com.cenk.valocase.inventory.domain.InventoryItem;
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
    void firstMarketClaim_grants2500_andLeavesThreeRemaining() {
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(0L);
        stubFreshMarketToken("ad-m1");
        stubMarketCredit(12500L);

        AdRewardClaimResponse r = service.claim(ACCOUNT, MARKET, marketReq("ad-m1"));

        assertEquals("OK", r.status());
        assertEquals(2500L, r.grantedVp());
        assertEquals(12500L, r.newVpBalance());
        assertEquals(3L, r.marketRemainingClaims());
        assertFalse(r.marketCooldownActive());
        assertEquals(0L, r.marketCooldownRemainingSeconds());
        ArgumentCaptor<AdRewardClaim> captor = ArgumentCaptor.forClass(AdRewardClaim.class);
        verify(adRewardClaimRepository).saveAndFlush(captor.capture());
        assertEquals(2500L, captor.getValue().getGrantedVp());
        assertTrue(captor.getValue().isConsumed());
        verify(walletService).credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any());
    }

    @Test
    void secondAndThirdMarketClaims_grant2500() {
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(eq(ACCOUNT), eq(MARKET), any()))
                .thenReturn(Optional.empty());
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(1L, 2L);
        stubMarketCredit(15000L);

        AdRewardClaimResponse second = service.claim(ACCOUNT, MARKET, marketReq("ad-m2"));
        assertEquals("OK", second.status());
        assertEquals(2500L, second.grantedVp());
        assertEquals(2L, second.marketRemainingClaims());
        assertFalse(second.marketCooldownActive());

        AdRewardClaimResponse third = service.claim(ACCOUNT, MARKET, marketReq("ad-m3"));
        assertEquals("OK", third.status());
        assertEquals(2500L, third.grantedVp());
        assertEquals(1L, third.marketRemainingClaims());
        assertFalse(third.marketCooldownActive());
    }

    @Test
    void fourthMarketClaim_grants2500_andStartsCooldown() {
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(3L);
        stubFreshMarketToken("ad-m4");
        stubMarketCredit(20000L);

        AdRewardClaimResponse r = service.claim(ACCOUNT, MARKET, marketReq("ad-m4"));

        assertEquals("OK", r.status());
        assertEquals(2500L, r.grantedVp());
        assertEquals(20000L, r.newVpBalance());
        assertEquals(0L, r.marketRemainingClaims());
        assertTrue(r.marketCooldownActive());
        assertEquals(900L, r.marketCooldownRemainingSeconds());
        assertFalse(r.isAvailable());
        verify(walletService).credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any());
    }

    @Test
    void marketClaim_duringCooldown_rejectedWithZeroVp() {
        stubFreshMarketToken("ad-m5");
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(4L);
        when(adRewardClaimRepository.findFirstByAccountIdAndRewardTypeOrderByCreatedAtDesc(ACCOUNT, MARKET))
                .thenReturn(Optional.of(marketClaimAt(NOW.minusSeconds(60))));
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), 20000L, NOW, null));

        AdRewardClaimResponse r = service.claim(ACCOUNT, MARKET, marketReq("ad-m5"));

        assertEquals("COOLDOWN", r.status());
        assertEquals(0L, r.grantedVp());
        assertEquals(20000L, r.newVpBalance());
        assertEquals(0L, r.marketRemainingClaims());
        assertTrue(r.marketCooldownActive());
        assertEquals(840L, r.marketCooldownRemainingSeconds());
        assertEquals(AdRewardService.CODE_MARKET_COOLDOWN, r.unavailableReason());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(adRewardClaimRepository, never()).saveAndFlush(any());
    }

    @Test
    void marketClaim_afterCooldownExpires_grantsAgain() {
        stubFreshMarketToken("ad-m6");
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(4L);
        when(adRewardClaimRepository.findFirstByAccountIdAndRewardTypeOrderByCreatedAtDesc(ACCOUNT, MARKET))
                .thenReturn(Optional.of(marketClaimAt(NOW.minusSeconds(16 * 60))));
        stubMarketCredit(22500L);

        AdRewardClaimResponse r = service.claim(ACCOUNT, MARKET, marketReq("ad-m6"));

        assertEquals("OK", r.status());
        assertEquals(2500L, r.grantedVp());
        assertEquals(22500L, r.newVpBalance());
        assertEquals(3L, r.marketRemainingClaims());
        assertFalse(r.marketCooldownActive());
        verify(walletService).credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any());
    }

    @Test
    void marketClaim_duplicateToken_doesNotCreditAgain() {
        AdRewardClaim prior = marketClaimAt(NOW);
        prior.setAdToken("ad-m1");
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, MARKET, "ad-m1")).thenReturn(Optional.of(prior));
        when(adRewardClaimRepository.countByAccountIdAndRewardType(ACCOUNT, MARKET)).thenReturn(1L);
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), 12500L, NOW, null));

        AdRewardClaimResponse r = service.claim(ACCOUNT, MARKET, marketReq("ad-m1"));

        assertEquals("DUPLICATE", r.status());
        assertEquals(2500L, r.grantedVp());
        assertEquals(12500L, r.newVpBalance());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(adRewardClaimRepository, never()).saveAndFlush(any());
    }

    @Test
    void upgradePlus5Claim_singleTarget_bindsContextAndPersistsBuff() {
        UUID itemId = UUID.randomUUID();
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.UPGRADE_PLUS_5, "ad-u1")).thenReturn(Optional.empty());
        when(inventoryItemRepository.findByIdInAndAccountId(any(), eq(ACCOUNT)))
                .thenReturn(List.of(inventoryItem(itemId)));
        when(skinRepository.findAllById(any())).thenReturn(List.of(activeSkin("skin_t")));
        when(adRewardClaimRepository.existsByAccountIdAndRewardTypeAndSourceRef(
                eq(ACCOUNT), eq(AdRewardType.UPGRADE_PLUS_5), any())).thenReturn(false);

        AdRewardClaimResponse r = service.claim(ACCOUNT, AdRewardType.UPGRADE_PLUS_5,
                new AdRewardClaimRequest("UPGRADE_PLUS_5", "ad-u1", null,
                        List.of(itemId.toString()), List.of("skin_t"), null));

        assertEquals("OK", r.status());
        assertTrue(r.upgradePlus5Active());
        ArgumentCaptor<AdRewardClaim> captor = ArgumentCaptor.forClass(AdRewardClaim.class);
        verify(adRewardClaimRepository).saveAndFlush(captor.capture());
        assertEquals(AdRewardType.UPGRADE_PLUS_5, captor.getValue().getRewardType());
        assertFalse(captor.getValue().isConsumed());
    }

    @Test
    void upgradePlus5Claim_rejectsMultipleTargets_andPersistsNothing() {
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(
                ACCOUNT, AdRewardType.UPGRADE_PLUS_5, "ad-u2")).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> service.claim(
                ACCOUNT, AdRewardType.UPGRADE_PLUS_5,
                new AdRewardClaimRequest("UPGRADE_PLUS_5", "ad-u2", null,
                        List.of(UUID.randomUUID().toString()), List.of("skin_t1", "skin_t2"), null)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(adRewardClaimRepository, never()).saveAndFlush(any());
    }

    private static InventoryItem inventoryItem(UUID id) {
        InventoryItem i = new InventoryItem();
        i.setId(id);
        i.setAccountId(ACCOUNT);
        return i;
    }

    private static Skin activeSkin(String id) {
        Skin s = new Skin();
        s.setId(id);
        s.setActive(true);
        return s;
    }

    private static final AdRewardType MARKET = AdRewardType.MARKET_VP_2500;

    private static AdRewardClaimRequest marketReq(String adToken) {
        return new AdRewardClaimRequest("MARKET_VP_2500", adToken, null, null, null, null);
    }

    private void stubFreshMarketToken(String adToken) {
        when(adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(ACCOUNT, MARKET, adToken))
                .thenReturn(Optional.empty());
    }

    private void stubMarketCredit(long resultingBalance) {
        Wallet wallet = new Wallet();
        wallet.setVpBalance(resultingBalance);
        when(walletService.credit(eq(ACCOUNT), eq(2500L), eq(AdRewardService.REASON_MARKET_VP), any()))
                .thenReturn(wallet);
    }

    private AdRewardClaim marketClaimAt(Instant createdAt) {
        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(ACCOUNT);
        claim.setRewardType(MARKET);
        claim.setGrantedVp(2500L);
        claim.setCreatedAt(createdAt);
        return claim;
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
