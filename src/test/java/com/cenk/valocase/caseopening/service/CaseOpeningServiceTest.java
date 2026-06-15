package com.cenk.valocase.caseopening.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.caseopening.domain.CaseOpening;
import com.cenk.valocase.caseopening.dto.OpenCaseResultResponse;
import com.cenk.valocase.caseopening.repository.CaseOpeningRepository;
import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.service.InventoryService;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class CaseOpeningServiceTest {

    @Mock private CaseDefinitionRepository caseDefinitionRepository;
    @Mock private CaseEntryRepository caseEntryRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private WalletService walletService;
    @Mock private InventoryService inventoryService;
    @Mock private CaseOpeningRepository caseOpeningRepository;
    @Mock private DropSelector dropSelector;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private CaseOpeningService caseOpeningService;

    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final String CASE_ID = "vandal_basic";

    private static CaseDefinition caseDef(boolean active, int price) {
        CaseDefinition c = new CaseDefinition();
        c.setId(CASE_ID);
        c.setDisplayName("Basic Vandal Case");
        c.setPriceVp(price);
        c.setActive(active);
        return c;
    }

    private static Skin skin(String id, boolean active) {
        Skin s = new Skin();
        s.setId(id);
        s.setDisplayName("Arcane Vandal");
        s.setWeapon("Vandal");
        s.setRarity("Exclusive");
        s.setVpValue(1775);
        s.setImageRef("Art/Skins/x");
        s.setActive(active);
        return s;
    }

    private static CaseEntry entry(String skinId, int weight) {
        CaseEntry e = new CaseEntry();
        e.setCaseId(CASE_ID);
        e.setSkinId(skinId);
        e.setWeight(weight);
        return e;
    }

    @Test
    void caseNotFound_throws404() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () -> caseOpeningService.open(ACCOUNT_ID, CASE_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(walletService, never()).debit(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void inactiveCase_throws404() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(false, 500)));

        ApiException ex = assertThrows(ApiException.class, () -> caseOpeningService.open(ACCOUNT_ID, CASE_ID));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void emptyDropPool_throws500_andNoMutation() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 500)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of());

        ApiException ex = assertThrows(ApiException.class, () -> caseOpeningService.open(ACCOUNT_ID, CASE_ID));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
        verify(walletService, never()).debit(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void happyPath_debitsPriceGrantsSkinAndReturnsResult() {
        String skinId = "skin_arcane_vandal_vandal";
        CaseEntry winning = entry(skinId, 1);
        UUID openingId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();

        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 500)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(winning));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin(skinId, true)));
        when(dropSelector.selectWeighted(any())).thenReturn(winning);
        when(caseOpeningRepository.save(any(CaseOpening.class))).thenAnswer(inv -> {
            CaseOpening o = inv.getArgument(0);
            o.setId(openingId);
            return o;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(9500L);
        when(walletService.debit(ACCOUNT_ID, 500L, CaseOpeningService.REASON_CASE_OPEN, openingId)).thenReturn(wallet);
        InventoryItem item = new InventoryItem();
        item.setId(itemId);
        when(inventoryService.addItem(ACCOUNT_ID, skinId, InventoryService.SOURCE_CASE_OPENING, openingId))
                .thenReturn(item);

        OpenCaseResultResponse result = caseOpeningService.open(ACCOUNT_ID, CASE_ID);

        assertEquals(openingId.toString(), result.openingId());
        assertEquals(CASE_ID, result.caseId());
        assertEquals(skinId, result.wonSkin().skinId());
        assertEquals(9500L, result.newVpBalance());
        assertEquals(itemId.toString(), result.inventoryItemId());

        verify(walletService).debit(ACCOUNT_ID, 500L, CaseOpeningService.REASON_CASE_OPEN, openingId);
        verify(inventoryService).addItem(ACCOUNT_ID, skinId, InventoryService.SOURCE_CASE_OPENING, openingId);
    }

    @Test
    void freeCase_skipsDebitAndReadsBalance() {
        String skinId = "skin_free";
        CaseEntry winning = entry(skinId, 1);
        UUID openingId = UUID.randomUUID();

        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 0)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(winning));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin(skinId, true)));
        when(dropSelector.selectWeighted(any())).thenReturn(winning);
        when(caseOpeningRepository.save(any(CaseOpening.class))).thenAnswer(inv -> {
            CaseOpening o = inv.getArgument(0);
            o.setId(openingId);
            return o;
        });
        when(walletService.getWalletForAccount(ACCOUNT_ID))
                .thenReturn(new com.cenk.valocase.wallet.dto.WalletResponse(ACCOUNT_ID.toString(), 10000L, null));
        InventoryItem item = new InventoryItem();
        item.setId(UUID.randomUUID());
        when(inventoryService.addItem(eq(ACCOUNT_ID), eq(skinId), eq(InventoryService.SOURCE_CASE_OPENING), eq(openingId)))
                .thenReturn(item);

        OpenCaseResultResponse result = caseOpeningService.open(ACCOUNT_ID, CASE_ID);

        assertEquals(10000L, result.newVpBalance());
        verify(walletService, never()).debit(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
    }
}
