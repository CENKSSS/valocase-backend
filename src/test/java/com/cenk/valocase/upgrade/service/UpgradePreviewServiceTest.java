package com.cenk.valocase.upgrade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.adreward.service.AdRewardService;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.inventory.service.InventoryService;
import com.cenk.valocase.upgrade.domain.Upgrade;
import com.cenk.valocase.upgrade.dto.UpgradePreviewResponse;
import com.cenk.valocase.upgrade.repository.UpgradeInputRepository;
import com.cenk.valocase.upgrade.repository.UpgradeRepository;
import com.cenk.valocase.upgrade.repository.UpgradeTargetRepository;

@ExtendWith(MockitoExtension.class)
class UpgradePreviewServiceTest {

    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private UpgradeRepository upgradeRepository;
    @Mock private UpgradeInputRepository upgradeInputRepository;
    @Mock private UpgradeTargetRepository upgradeTargetRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AdRewardService adRewardService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private final UpgradeChanceCalculator calculator = new UpgradeChanceCalculator(() -> 0.0);
    private UpgradeService upgradeService;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String TARGET = "skin_target";

    @BeforeEach
    void setUp() {
        upgradeService = new UpgradeService(inventoryItemRepository, skinRepository, upgradeRepository,
                upgradeInputRepository, upgradeTargetRepository, inventoryService, calculator,
                adRewardService, eventPublisher);
    }

    private static Skin skin(String id, int vp, boolean active) {
        Skin s = new Skin();
        s.setId(id);
        s.setVpValue(vp);
        s.setActive(active);
        return s;
    }

    private static Skin meleeSkin(String id, int vp) {
        Skin s = skin(id, vp, true);
        s.setWeapon("Melee");
        return s;
    }

    private static InventoryItem item(UUID id, String skinId) {
        InventoryItem i = new InventoryItem();
        i.setId(id);
        i.setAccountId(ACCOUNT);
        i.setSkinId(skinId);
        return i;
    }

    @Test
    void validNonMelee_returnsSameChanceAsCalculator() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 4350, true), skin("skin_in", 3550, true)));

        UpgradePreviewResponse response = upgradeService.preview(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertTrue(response.canUpgrade());
        assertNull(response.reason());
        assertEquals(3550, response.inputValue());
        assertEquals(4350, response.targetValue());
        assertEquals(calculator.computeChance(3550, 4350, false, 0), response.chancePercent(), 0.0001);
    }

    @Test
    void meleeTarget_capsAtFivePercent() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(meleeSkin(TARGET, 4350), skin("skin_in", 4000, true)));

        UpgradePreviewResponse response = upgradeService.preview(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertTrue(response.canUpgrade());
        assertEquals(5.0, response.chancePercent(), 0.0001);
    }

    @Test
    void nonMelee_capsAtGlobalSixtyFive() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 4350, true), skin("skin_in", 4000, true)));

        UpgradePreviewResponse response = upgradeService.preview(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertEquals(65.0, response.chancePercent(), 0.0001);
    }

    @Test
    void multipleMeleeInputs_penaltyAppliesBeforeCaps() {
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(i1, "m1"), item(i2, "m2")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 4350, true), meleeSkin("m1", 870), meleeSkin("m2", 870)));

        UpgradePreviewResponse response = upgradeService.preview(
                ACCOUNT, List.of(i1.toString(), i2.toString()), TARGET);

        // 1740/4350 -> 40% raw, halved by two Melee inputs -> 20%, under the 65% cap.
        assertEquals(20.0, response.chancePercent(), 0.0001);
        assertEquals(calculator.computeChance(1740, 4350, false, 2), response.chancePercent(), 0.0001);
    }

    @Test
    void inputGreaterThanTarget_returnsBlockedZeroChance() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 3550, true), skin("skin_in", 5000, true)));

        UpgradePreviewResponse response = upgradeService.preview(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertFalse(response.canUpgrade());
        assertEquals(0.0, response.chancePercent(), 0.0001);
        assertEquals(UpgradeService.CODE_UPGRADE_NOT_POSSIBLE, response.reason());
        assertEquals(5000, response.inputValue());
        assertEquals(3550, response.targetValue());
    }

    @Test
    void moreThanFourInputs_throws400() {
        List<String> fiveInputs = List.of(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.preview(ACCOUNT, fiveInputs, TARGET));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inventoryItemRepository, never()).findByIdInAndAccountId(any(), any());
    }

    @Test
    void preview_hasNoSideEffects() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 4350, true), skin("skin_in", 3550, true)));

        upgradeService.preview(ACCOUNT, List.of(itemId.toString()), TARGET);

        verify(inventoryItemRepository, never()).findForUpdateByIdInAndAccountId(any(), any());
        verify(inventoryItemRepository, never()).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
        verify(upgradeRepository, never()).saveAndFlush(any(Upgrade.class));
        verify(upgradeInputRepository, never()).saveAll(any());
        verify(upgradeTargetRepository, never()).saveAll(any());
        verify(eventPublisher, never()).publishEvent(any());
    }
}
