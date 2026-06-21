package com.cenk.valocase.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.dto.CaseDetailResponse;
import com.cenk.valocase.catalog.dto.CaseDropResponse;
import com.cenk.valocase.catalog.dto.CaseSummaryResponse;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private SkinRepository skinRepository;
    @Mock private CaseDefinitionRepository caseDefinitionRepository;
    @Mock private CaseEntryRepository caseEntryRepository;
    @Mock private WalletService walletService;

    private CatalogService service;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String MELEE_CASE = "melee_protocol";
    private static final String CLASSIC_CASE = "classic_basic";

    @BeforeEach
    void setUp() {
        service = new CatalogService(skinRepository, caseDefinitionRepository,
                caseEntryRepository, new ProgressionService(), walletService);
    }

    private static CaseDefinition caseDef(String id, int price, boolean active) {
        CaseDefinition c = new CaseDefinition();
        c.setId(id);
        c.setDisplayName(id);
        c.setPriceVp(price);
        c.setActive(active);
        return c;
    }

    private static CaseEntry entry(String caseId, String skinId, int weight) {
        CaseEntry e = new CaseEntry();
        e.setCaseId(caseId);
        e.setSkinId(skinId);
        e.setWeight(weight);
        return e;
    }

    private static Skin skin(String id, int vp, boolean active) {
        Skin s = new Skin();
        s.setId(id);
        s.setDisplayName(id);
        s.setWeapon("Vandal");
        s.setRarity("Premium");
        s.setVpValue(vp);
        s.setActive(active);
        return s;
    }

    private static Account account(int level) {
        Account a = new Account();
        a.setId(ACCOUNT);
        a.setLevel(level);
        return a;
    }

    private void stubWallet(long balance) {
        when(walletService.getWalletForAccount(ACCOUNT))
                .thenReturn(new WalletResponse(ACCOUNT.toString(), balance, Instant.now(), null));
    }

    @Test
    void caseList_returnsBackendPriceAndCategory_anonymous() {
        when(caseDefinitionRepository.findByActiveTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(caseDef(CLASSIC_CASE, 500, true)));

        List<CaseSummaryResponse> cases = service.getActiveCases(null);

        assertEquals(1, cases.size());
        CaseSummaryResponse summary = cases.get(0);
        assertEquals(500, summary.priceVp());
        assertEquals("CLASSIC", summary.weaponCategory());
        assertEquals(1, summary.requiredLevel());
        assertNull(summary.canOpen());
        assertNull(summary.currentLevel());
        assertNull(summary.affordable());
    }

    @Test
    void caseDetail_excludesDisabledSkins() {
        when(caseDefinitionRepository.findById(CLASSIC_CASE)).thenReturn(Optional.of(caseDef(CLASSIC_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CLASSIC_CASE))
                .thenReturn(List.of(entry(CLASSIC_CASE, "skin_a", 10), entry(CLASSIC_CASE, "skin_b", 10)));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin("skin_a", 100, true), skin("skin_b", 100, false)));

        CaseDetailResponse detail = service.getCaseDetail(CLASSIC_CASE, null);

        assertEquals(1, detail.drops().size());
        assertEquals("skin_a", detail.drops().get(0).skinId());
    }

    @Test
    void caseDetail_excludesNonPositiveWeight_matchingRollEligibility() {
        when(caseDefinitionRepository.findById(CLASSIC_CASE)).thenReturn(Optional.of(caseDef(CLASSIC_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CLASSIC_CASE))
                .thenReturn(List.of(entry(CLASSIC_CASE, "skin_a", 10), entry(CLASSIC_CASE, "skin_c", 0)));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin("skin_a", 100, true), skin("skin_c", 100, true)));

        CaseDetailResponse detail = service.getCaseDetail(CLASSIC_CASE, null);

        assertEquals(1, detail.drops().size());
        assertEquals("skin_a", detail.drops().get(0).skinId());
    }

    @Test
    void caseDetail_computesOddsAndExpectedValueFromEligiblePool() {
        when(caseDefinitionRepository.findById(CLASSIC_CASE)).thenReturn(Optional.of(caseDef(CLASSIC_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CLASSIC_CASE))
                .thenReturn(List.of(entry(CLASSIC_CASE, "skin_a", 3), entry(CLASSIC_CASE, "skin_b", 1)));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin("skin_a", 100, true), skin("skin_b", 500, true)));

        CaseDetailResponse detail = service.getCaseDetail(CLASSIC_CASE, null);

        CaseDropResponse a = detail.drops().stream().filter(d -> d.skinId().equals("skin_a")).findFirst().orElseThrow();
        CaseDropResponse b = detail.drops().stream().filter(d -> d.skinId().equals("skin_b")).findFirst().orElseThrow();
        assertEquals(0.75, a.dropChance(), 0.0001);
        assertEquals(0.25, b.dropChance(), 0.0001);
        assertEquals(200L, detail.expectedValueVp());
    }

    @Test
    void lockedCase_returnsRequiredLevelAndCannotOpen() {
        when(caseDefinitionRepository.findById(MELEE_CASE)).thenReturn(Optional.of(caseDef(MELEE_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(MELEE_CASE)).thenReturn(List.of());
        when(skinRepository.findAllById(any())).thenReturn(List.of());
        stubWallet(1_000L);

        CaseDetailResponse detail = service.getCaseDetail(MELEE_CASE, account(1));

        assertFalse(detail.canOpen());
        assertEquals(CatalogService.LOCKED_LEVEL, detail.lockedReason());
        assertEquals(15, detail.requiredLevel());
        assertEquals(1, detail.currentLevel());
        assertEquals("MELEE", detail.weaponCategory());
    }

    @Test
    void unlockedAffordableCase_canOpen() {
        when(caseDefinitionRepository.findById(MELEE_CASE)).thenReturn(Optional.of(caseDef(MELEE_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(MELEE_CASE)).thenReturn(List.of());
        when(skinRepository.findAllById(any())).thenReturn(List.of());
        stubWallet(1_000L);

        CaseDetailResponse detail = service.getCaseDetail(MELEE_CASE, account(20));

        assertTrue(detail.canOpen());
        assertNull(detail.lockedReason());
        assertTrue(detail.affordable());
        assertEquals(20, detail.currentLevel());
    }

    @Test
    void unlockedButUnaffordable_canOpenButNotAffordable() {
        when(caseDefinitionRepository.findById(CLASSIC_CASE)).thenReturn(Optional.of(caseDef(CLASSIC_CASE, 100, true)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CLASSIC_CASE)).thenReturn(List.of());
        when(skinRepository.findAllById(any())).thenReturn(List.of());
        stubWallet(50L);

        CaseDetailResponse detail = service.getCaseDetail(CLASSIC_CASE, account(5));

        assertTrue(detail.canOpen());
        assertFalse(detail.affordable());
    }

    @Test
    void inactiveCase_representedAsInactiveAndLocked() {
        when(caseDefinitionRepository.findById(CLASSIC_CASE)).thenReturn(Optional.of(caseDef(CLASSIC_CASE, 100, false)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CLASSIC_CASE)).thenReturn(List.of());
        when(skinRepository.findAllById(any())).thenReturn(List.of());
        stubWallet(1_000L);

        CaseDetailResponse detail = service.getCaseDetail(CLASSIC_CASE, account(20));

        assertFalse(detail.active());
        assertFalse(detail.canOpen());
        assertEquals(CatalogService.LOCKED_INACTIVE, detail.lockedReason());
    }

    @Test
    void authenticatedList_usesPlayerState() {
        when(caseDefinitionRepository.findByActiveTrueOrderByDisplayNameAsc())
                .thenReturn(List.of(caseDef(CLASSIC_CASE, 100, true)));
        stubWallet(1_000L);

        List<CaseSummaryResponse> cases = service.getActiveCases(account(5));

        CaseSummaryResponse summary = cases.get(0);
        assertTrue(summary.canOpen());
        assertTrue(summary.affordable());
        assertEquals(5, summary.currentLevel());
    }
}
