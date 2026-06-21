package com.cenk.valocase.inventory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.dto.SellOneResponse;
import com.cenk.valocase.inventory.dto.SellResultResponse;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock private InventoryItemRepository inventoryItemRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private WalletService walletService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private InventoryService inventoryService;

    private static final UUID ACCOUNT = UUID.randomUUID();

    private static Skin skin(String id, int vp) {
        Skin s = new Skin();
        s.setId(id);
        s.setVpValue(vp);
        s.setActive(true);
        return s;
    }

    private static InventoryItem item(UUID id, String skinId) {
        InventoryItem i = new InventoryItem();
        i.setId(id);
        i.setAccountId(ACCOUNT);
        i.setSkinId(skinId);
        return i;
    }

    private static Wallet wallet(long balance) {
        Wallet w = new Wallet();
        w.setVpBalance(balance);
        return w;
    }

    @Test
    void sellOne_nullSkinId_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> inventoryService.sellOne(ACCOUNT, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void sellOne_notOwnedOrConsumed_throws404() {
        when(inventoryItemRepository.findFirstByAccountIdAndSkinIdOrderByAcquiredAtAsc(ACCOUNT, "skin_a"))
                .thenReturn(java.util.Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> inventoryService.sellOne(ACCOUNT, "skin_a"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(inventoryItemRepository, never()).delete(any());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void sellOne_success_creditsWalletAndDeletesItem() {
        UUID itemId = UUID.randomUUID();
        InventoryItem owned = item(itemId, "skin_a");
        when(inventoryItemRepository.findFirstByAccountIdAndSkinIdOrderByAcquiredAtAsc(ACCOUNT, "skin_a"))
                .thenReturn(java.util.Optional.of(owned));
        when(skinRepository.findById("skin_a")).thenReturn(java.util.Optional.of(skin("skin_a", 1000)));
        when(walletService.credit(eq(ACCOUNT), eq(1000L), eq(InventoryService.REASON_INVENTORY_SELL), eq(itemId)))
                .thenReturn(wallet(11000L));

        SellOneResponse result = inventoryService.sellOne(ACCOUNT, "skin_a");

        assertEquals("skin_a", result.skinId());
        assertEquals(1000L, result.vpGained());
        assertEquals(11000L, result.newVpBalance());
        verify(inventoryItemRepository).delete(owned);
    }

    @Test
    void sellAll_empty_throws422() {
        when(inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(ACCOUNT)).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> inventoryService.sellAll(ACCOUNT));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void sellAll_success_creditsTotalAndDeletesAll() {
        InventoryItem i1 = item(UUID.randomUUID(), "skin_a");
        InventoryItem i2 = item(UUID.randomUUID(), "skin_b");
        when(inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(ACCOUNT)).thenReturn(List.of(i1, i2));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin("skin_a", 1000), skin("skin_b", 500)));
        when(walletService.credit(eq(ACCOUNT), eq(1500L), eq(InventoryService.REASON_INVENTORY_SELL), isNull()))
                .thenReturn(wallet(11500L));

        SellResultResponse result = inventoryService.sellAll(ACCOUNT);

        assertEquals(2, result.soldCount());
        assertEquals(1500L, result.totalVpGained());
        assertEquals(11500L, result.newVpBalance());
        verify(inventoryItemRepository).deleteAll(any());
    }

    @Test
    void sellBelowValue_negativeMax_throws400() {
        ApiException ex = assertThrows(ApiException.class, () -> inventoryService.sellBelowValue(ACCOUNT, -1));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void sellBelowValue_nothingEligible_throws422() {
        InventoryItem expensive = item(UUID.randomUUID(), "skin_c");
        when(inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(ACCOUNT)).thenReturn(List.of(expensive));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin("skin_c", 5000)));

        ApiException ex = assertThrows(ApiException.class, () -> inventoryService.sellBelowValue(ACCOUNT, 1000));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void sellBelowValue_success_sellsOnlyEligibleItems() {
        InventoryItem cheap = item(UUID.randomUUID(), "skin_b");
        InventoryItem mid = item(UUID.randomUUID(), "skin_a");
        InventoryItem expensive = item(UUID.randomUUID(), "skin_c");
        when(inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(ACCOUNT))
                .thenReturn(List.of(mid, cheap, expensive));
        when(skinRepository.findAllById(any()))
                .thenReturn(List.of(skin("skin_a", 1000), skin("skin_b", 100), skin("skin_c", 5000)));
        when(walletService.credit(eq(ACCOUNT), eq(1100L), eq(InventoryService.REASON_INVENTORY_SELL), isNull()))
                .thenReturn(wallet(11100L));

        SellResultResponse result = inventoryService.sellBelowValue(ACCOUNT, 1000);

        assertEquals(2, result.soldCount());
        assertEquals(1100L, result.totalVpGained());
        assertEquals(11100L, result.newVpBalance());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<InventoryItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(inventoryItemRepository).deleteAll(captor.capture());
        assertEquals(List.of(mid, cheap), captor.getValue());
    }
}
