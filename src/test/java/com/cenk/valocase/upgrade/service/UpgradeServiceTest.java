package com.cenk.valocase.upgrade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import com.cenk.valocase.upgrade.dto.UpgradeResultResponse;
import com.cenk.valocase.upgrade.repository.UpgradeInputRepository;
import com.cenk.valocase.upgrade.repository.UpgradeRepository;
import com.cenk.valocase.upgrade.repository.UpgradeTargetRepository;

@ExtendWith(MockitoExtension.class)
class UpgradeServiceTest {

    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private UpgradeRepository upgradeRepository;
    @Mock private UpgradeInputRepository upgradeInputRepository;
    @Mock private UpgradeTargetRepository upgradeTargetRepository;
    @Mock private InventoryService inventoryService;
    @Mock private UpgradeChanceCalculator chanceCalculator;
    @Mock private AdRewardService adRewardService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UpgradeService upgradeService;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String TARGET = "skin_target";

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

    private static InventoryItem inventoryItem(UUID id) {
        InventoryItem i = new InventoryItem();
        i.setId(id);
        return i;
    }

    @Test
    void emptyInputs_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(), TARGET));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void duplicateInputs_throws400() {
        String id = UUID.randomUUID().toString();
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(id, id), TARGET));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void malformedUuid_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of("not-a-uuid"), TARGET));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void targetNotFound_throws404() {
        when(skinRepository.findAllById(any())).thenReturn(List.of());
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(UUID.randomUUID().toString()), TARGET));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void targetInactive_throws404() {
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin(TARGET, 5000, false)));
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(UUID.randomUUID().toString()), TARGET));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void inputNotOwned_throws404() {
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin(TARGET, 5000, true)));
        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any())).thenReturn(List.of());
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(UUID.randomUUID().toString()), TARGET));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void targetNotHigherThanInput_throws422() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 5000, true), skin("skin_in", 10000, true)));

        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(itemId.toString()), TARGET));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void success_consumesInputsAndGrantsTarget() {
        UUID itemId = UUID.randomUUID();
        UUID upgradeId = UUID.randomUUID();
        UUID grantedId = UUID.randomUUID();

        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 5000, true), skin("skin_in", 1000, true)));
        when(chanceCalculator.computeChance(1000L, 5000L, false, 0, 0.0)).thenReturn(20.0);
        when(chanceCalculator.roll(20.0)).thenReturn(true);
        when(upgradeRepository.saveAndFlush(any(Upgrade.class))).thenAnswer(inv -> {
            Upgrade u = inv.getArgument(0);
            u.setId(upgradeId);
            return u;
        });
        InventoryItem granted = new InventoryItem();
        granted.setId(grantedId);
        when(inventoryService.addItem(ACCOUNT, TARGET, UpgradeService.INVENTORY_SOURCE_UPGRADE, null))
                .thenReturn(granted);

        UpgradeResultResponse result = upgradeService.upgrade(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertTrue(result.success());
        assertEquals(upgradeId.toString(), result.upgradeId());
        assertEquals(20.0, result.chance(), 0.0001);
        assertEquals(List.of(itemId.toString()), result.consumedItemIds());
        assertEquals(grantedId.toString(), result.grantedInventoryItemId());
        verify(inventoryItemRepository).deleteAll(any());
        verify(inventoryService).addItem(ACCOUNT, TARGET, UpgradeService.INVENTORY_SOURCE_UPGRADE, null);
    }

    @Test
    void partialOwnership_throws404_andConsumesNothing() {
        UUID owned = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin(TARGET, 5000, true)));
        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(owned, "skin_in")));

        ApiException ex = assertThrows(ApiException.class, () -> upgradeService.upgrade(
                ACCOUNT, List.of(owned.toString(), missing.toString()), TARGET));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(inventoryItemRepository, never()).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void blankTarget_throws400() {
        String id = UUID.randomUUID().toString();
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(id), List.of("  ")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void duplicateTargets_throws400() {
        String id = UUID.randomUUID().toString();
        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(id), List.of(TARGET, TARGET)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void multipleTargets_throws400_consumesNothing() {
        UUID itemId = UUID.randomUUID();

        ApiException ex = assertThrows(ApiException.class, () -> upgradeService.upgrade(
                ACCOUNT, List.of(itemId.toString()), List.of("skin_t1", "skin_t2")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inventoryItemRepository, never()).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
        verify(chanceCalculator, never()).roll(anyDouble());
    }

    @Test
    void moreThanFourInputs_throws400_consumesNothing() {
        List<String> fiveInputs = List.of(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString());

        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, fiveInputs, TARGET));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(inventoryItemRepository, never()).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
        verify(chanceCalculator, never()).roll(anyDouble());
    }

    @Test
    void fourInputsOneTarget_succeeds() {
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        UUID i3 = UUID.randomUUID();
        UUID i4 = UUID.randomUUID();
        UUID upgradeId = UUID.randomUUID();
        UUID grantedId = UUID.randomUUID();

        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(i1, "skin_in"), item(i2, "skin_in"),
                        item(i3, "skin_in"), item(i4, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 5000, true), skin("skin_in", 1000, true)));
        when(chanceCalculator.computeChance(4000L, 5000L, false, 0, 0.0)).thenReturn(60.0);
        when(chanceCalculator.roll(60.0)).thenReturn(true);
        when(upgradeRepository.saveAndFlush(any(Upgrade.class))).thenAnswer(inv -> {
            Upgrade u = inv.getArgument(0);
            u.setId(upgradeId);
            return u;
        });
        when(inventoryService.addItem(ACCOUNT, TARGET, UpgradeService.INVENTORY_SOURCE_UPGRADE, null))
                .thenReturn(inventoryItem(grantedId));

        UpgradeResultResponse result = upgradeService.upgrade(
                ACCOUNT, List.of(i1.toString(), i2.toString(), i3.toString(), i4.toString()), TARGET);

        assertTrue(result.success());
        assertEquals(List.of(TARGET), result.targetSkinIds());
        assertEquals(grantedId.toString(), result.grantedInventoryItemId());
        verify(inventoryItemRepository).deleteAll(any());
        verify(inventoryService).addItem(ACCOUNT, TARGET, UpgradeService.INVENTORY_SOURCE_UPGRADE, null);
    }

    @Test
    void inputGreaterThanTarget_blocksCleanly_rollsNothing_consumesNothing_grantsNothing() {
        UUID itemId = UUID.randomUUID();
        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 5000, true), skin("skin_in", 8000, true)));

        ApiException ex = assertThrows(ApiException.class,
                () -> upgradeService.upgrade(ACCOUNT, List.of(itemId.toString()), TARGET));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals(UpgradeService.CODE_UPGRADE_NOT_POSSIBLE, ex.getCode());
        verify(chanceCalculator, never()).roll(anyDouble());
        verify(inventoryItemRepository, never()).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void equalValueAllowed_forwardsMeleeFlags_andDisplayedChanceMatchesRoll() {
        UUID i1 = UUID.randomUUID();
        UUID i2 = UUID.randomUUID();
        UUID upgradeId = UUID.randomUUID();

        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(i1, "m1"), item(i2, "m2")));
        when(skinRepository.findAllById(any())).thenReturn(List.of(
                meleeSkin(TARGET, 10000), meleeSkin("m1", 5000), meleeSkin("m2", 5000)));
        when(chanceCalculator.computeChance(10000L, 10000L, true, 2, 0.0)).thenReturn(5.0);
        when(chanceCalculator.roll(5.0)).thenReturn(false);
        when(upgradeRepository.saveAndFlush(any(Upgrade.class))).thenAnswer(inv -> {
            Upgrade u = inv.getArgument(0);
            u.setId(upgradeId);
            return u;
        });

        UpgradeResultResponse result = upgradeService.upgrade(
                ACCOUNT, List.of(i1.toString(), i2.toString()), TARGET);

        assertFalse(result.success());
        assertEquals(5.0, result.chance(), 0.0001);
        verify(chanceCalculator).computeChance(10000L, 10000L, true, 2, 0.0);
        verify(chanceCalculator).roll(5.0);
        verify(inventoryItemRepository).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void failure_consumesInputsButGrantsNothing() {
        UUID itemId = UUID.randomUUID();
        UUID upgradeId = UUID.randomUUID();

        when(inventoryItemRepository.findForUpdateByIdInAndAccountId(any(), any()))
                .thenReturn(List.of(item(itemId, "skin_in")));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin(TARGET, 5000, true), skin("skin_in", 1000, true)));
        when(chanceCalculator.computeChance(1000L, 5000L, false, 0, 0.0)).thenReturn(20.0);
        when(chanceCalculator.roll(20.0)).thenReturn(false);
        when(upgradeRepository.saveAndFlush(any(Upgrade.class))).thenAnswer(inv -> {
            Upgrade u = inv.getArgument(0);
            u.setId(upgradeId);
            return u;
        });

        UpgradeResultResponse result = upgradeService.upgrade(ACCOUNT, List.of(itemId.toString()), TARGET);

        assertFalse(result.success());
        assertNull(result.grantedInventoryItemId());
        assertEquals(List.of(itemId.toString()), result.consumedItemIds());
        verify(inventoryItemRepository).deleteAll(any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }
}
