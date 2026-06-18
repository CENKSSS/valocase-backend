package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.LobbyStatus;
import com.cenk.valocase.battle.domain.SlotType;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.repository.BattleLobbyRepository;
import com.cenk.valocase.battle.repository.BattleLobbySlotRepository;
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
import com.cenk.valocase.progression.CategoryLockedException;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BattleLobbyServiceTest {

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
    @Mock private BattleLobbyRepository lobbyRepository;
    @Mock private BattleLobbySlotRepository slotRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ProgressionService progressionService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BattleLobbyService service;

    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID JOINER = UUID.randomUUID();
    private static final UUID LOBBY = UUID.randomUUID();
    private static final String CASE_ID = "classic_basic";

    private static Account account(UUID id, int level) {
        Account a = new Account();
        a.setId(id);
        a.setLevel(level);
        a.setDisplayName("Player");
        return a;
    }

    private static CaseDefinition caseDef(int price) {
        CaseDefinition c = new CaseDefinition();
        c.setId(CASE_ID);
        c.setDisplayName("Classic Basic");
        c.setPriceVp(price);
        c.setActive(true);
        return c;
    }

    private static CaseEntry entry() {
        CaseEntry e = new CaseEntry();
        e.setCaseId(CASE_ID);
        e.setSkinId("skin_a");
        e.setWeight(1);
        return e;
    }

    private static Skin skin() {
        Skin s = new Skin();
        s.setId("skin_a");
        s.setVpValue(500);
        s.setActive(true);
        return s;
    }

    private static BattleLobbySlot slot(int index, SlotType type, UUID account, boolean creator) {
        BattleLobbySlot s = new BattleLobbySlot();
        s.setId(UUID.randomUUID());
        s.setLobbyId(LOBBY);
        s.setSlotIndex(index);
        s.setSlotType(type);
        s.setAccountId(account);
        s.setCreator(creator);
        s.setChargedVp(account != null ? 200L : 0L);
        return s;
    }

    private void stubLobbySave() {
        when(lobbyRepository.saveAndFlush(any(BattleLobby.class))).thenAnswer(inv -> {
            BattleLobby l = inv.getArgument(0);
            l.setId(LOBBY);
            return l;
        });
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void create_chargesCreator_andOpensWaitingLobby() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        stubLobbySave();
        when(walletService.debit(eq(CREATOR), eq(200L), any(), any())).thenReturn(new Wallet());

        LobbyResponse res = service.createLobby(CREATOR, CASE_ID, 2, 2);

        assertEquals(LobbyStatus.WAITING.name(), res.status());
        assertEquals(200L, res.entryCost()); // price 100 x 2 rounds
        assertEquals(2, res.maxSlots());
        assertEquals(1, res.filledSlots());
        assertEquals(2, res.slots().size());
        verify(walletService).debit(eq(CREATOR), eq(200L), any(), eq(LOBBY));
    }

    @Test
    void create_lockedCase_throwsAndDoesNotCharge() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 1)));
        when(progressionService.isCategoryUnlocked(eq(1), any(CaseCategory.class))).thenReturn(false);

        assertThrows(CategoryLockedException.class, () -> service.createLobby(CREATOR, CASE_ID, 2, 2));
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void join_ownLobby_rejected() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(CREATOR, LOBBY));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void addBot_beforeDelay_rejected() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now()); // within the 3s window
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.addBot(CREATOR, LOBBY));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void addBot_byNonCreator_rejected() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.addBot(JOINER, LOBBY));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void getLobby_full_andDue_resolvesAndGrantsToRealWinner() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(2);
        lobby.setMaxSlots(2);
        lobby.setEntryCost(200L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS)); // due

        List<BattleLobbySlot> slots = List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.BOT, null, false));

        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(slots);
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry()));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin()));
        when(dropSelector.selectWeighted(any())).thenReturn(entry());
        when(battleResolver.winningIndex(any())).thenReturn(0); // creator (real) wins
        when(battleRepository.saveAndFlush(any(Battle.class))).thenAnswer(inv -> {
            Battle b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(inventoryService.addItem(eq(CREATOR), eq("skin_a"), any(), any())).thenAnswer(inv -> {
            InventoryItem item = new InventoryItem();
            item.setId(UUID.randomUUID());
            return item;
        });
        when(battleParticipantRepository.findByBattleIdOrderByParticipantIndexAsc(any())).thenReturn(List.of());
        when(battleRollRepository.findByBattleId(any())).thenReturn(List.of());

        LobbyResponse res = service.getLobby(LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        assertEquals(Integer.valueOf(0), res.winnerSlotIndex());
        // 2 slots x 2 rounds = 4 rolled skins, all granted to the real winner.
        verify(inventoryService, times(4)).addItem(eq(CREATOR), eq("skin_a"), any(), any());
    }
}
