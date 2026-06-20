package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.dto.BattleResultResponse;
import com.cenk.valocase.battle.repository.BattleParticipantRepository;
import com.cenk.valocase.battle.repository.BattleRepository;
import com.cenk.valocase.battle.repository.BattleRollRepository;
import com.cenk.valocase.caseopening.service.DropSelector;
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
import com.cenk.valocase.wallet.service.InsufficientFundsException;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class BotBattleServiceTest {

    @Mock private CaseDefinitionRepository caseDefinitionRepository;
    @Mock private CaseEntryRepository caseEntryRepository;
    @Mock private SkinRepository skinRepository;
    @Mock private WalletService walletService;
    @Mock private InventoryService inventoryService;
    @Mock private DropSelector dropSelector;
    @Mock private BattleResolver battleResolver;
    @Mock private BattleRepository battleRepository;
    @Mock private BattleParticipantRepository battleParticipantRepository;
    @Mock private BattleRollRepository battleRollRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BotBattleService service;

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final String CASE_ID = "vandal_basic";

    private static CaseDefinition caseDef(boolean active, int price) {
        CaseDefinition c = new CaseDefinition();
        c.setId(CASE_ID);
        c.setPriceVp(price);
        c.setActive(active);
        return c;
    }

    private static CaseEntry entry(String skinId) {
        CaseEntry e = new CaseEntry();
        e.setCaseId(CASE_ID);
        e.setSkinId(skinId);
        e.setWeight(1);
        return e;
    }

    private static Skin skin(String id, int vp, boolean active) {
        Skin s = new Skin();
        s.setId(id);
        s.setVpValue(vp);
        s.setActive(active);
        return s;
    }

    /** Stubs the case/pool load and the battle header save for the happy path. */
    private void stubActivePool() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry("skin_a")));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin("skin_a", 1000, true)));
        when(dropSelector.selectWeighted(any())).thenReturn(entry("skin_a"));
        when(battleRepository.saveAndFlush(any(Battle.class))).thenAnswer(inv -> {
            Battle b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
    }

    @Test
    void invalidRounds_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 0, 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void tooManyRounds_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 6, 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void invalidParticipantCount_throws400() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 1));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        ApiException ex2 = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 5));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.getStatus());
    }

    @Test
    void unknownCase_throws404() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.empty());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 2));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void inactiveCase_throws404() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(false, 100)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 2));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void emptyPool_throws500() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 2));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    @Test
    void noActiveSkinsInPool_throws500() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(true, 100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry("skin_a")));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin("skin_a", 1000, false)));
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 3, 2));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }

    @Test
    void insufficientVp_throws422_andGrantsNothing() {
        stubActivePool();
        when(battleResolver.winningIndex(any())).thenReturn(0);
        when(walletService.debit(eq(ACCOUNT), anyLong(), any(), any()))
                .thenThrow(new InsufficientFundsException(50, 200));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 2, 2));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void userWins_grantsAllRolledSkins() {
        stubActivePool();
        when(battleResolver.winningIndex(any())).thenReturn(0);
        Wallet wallet = new Wallet();
        wallet.setVpBalance(9800L);
        when(walletService.debit(eq(ACCOUNT), eq(200L), any(), any())).thenReturn(wallet);
        when(inventoryService.addItem(eq(ACCOUNT), eq("skin_a"), eq(BotBattleService.INVENTORY_SOURCE_BATTLE_REWARD), any()))
                .thenAnswer(inv -> {
                    InventoryItem item = new InventoryItem();
                    item.setId(UUID.randomUUID());
                    return item;
                });

        // participantCount 2 x rounds 2 = 4 rolled skins, all granted on win.
        BattleResultResponse result = service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 2, 2);

        assertTrue(result.userWon());
        assertEquals(0, result.winnerIndex());
        assertEquals(200L, result.entryCost());
        assertEquals(9800L, result.newVpBalance());
        assertEquals(2, result.participants().size());
        assertEquals(4, result.grantedInventoryItemIds().size());
        verify(inventoryService, times(4))
                .addItem(eq(ACCOUNT), eq("skin_a"), eq(BotBattleService.INVENTORY_SOURCE_BATTLE_REWARD), any());
    }

    @Test
    void userLoses_grantsNothing() {
        stubActivePool();
        when(battleResolver.winningIndex(any())).thenReturn(1);
        Wallet wallet = new Wallet();
        wallet.setVpBalance(9800L);
        when(walletService.debit(eq(ACCOUNT), eq(200L), any(), any())).thenReturn(wallet);

        BattleResultResponse result = service.createAndResolve(ACCOUNT, "Tester", "avatar_1", CASE_ID, 2, 2);

        assertFalse(result.userWon());
        assertEquals(1, result.winnerIndex());
        assertTrue(result.grantedInventoryItemIds().isEmpty());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }
}
