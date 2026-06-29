package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
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
import org.mockito.ArgumentCaptor;
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
import com.cenk.valocase.battle.dto.CaseSelectionRequest;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.repository.BattleLobbyCaseRepository;
import com.cenk.valocase.battle.repository.BattleLobbyRepository;
import com.cenk.valocase.battle.repository.BattleLobbySlotRepository;
import com.cenk.valocase.battle.repository.BattleParticipantRepository;
import com.cenk.valocase.battle.repository.BattleRepository;
import com.cenk.valocase.battle.repository.BattleRollRepository;
import com.cenk.valocase.caseopening.service.CaseRarityRoll;
import com.cenk.valocase.caseopening.service.CaseRarityRoll.RarityBucket;
import com.cenk.valocase.caseopening.service.DropSelector;
import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.CaseRarityWeightRepository;
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
    @Mock private CaseRarityWeightRepository caseRarityWeightRepository;
    @Mock private WalletService walletService;
    @Mock private InventoryService inventoryService;
    @Mock private DropSelector dropSelector;
    @Mock private CaseRarityRoll caseRarityRoll;
    @Mock private BattleResolver battleResolver;
    @Mock private BattleRepository battleRepository;
    @Mock private BattleParticipantRepository battleParticipantRepository;
    @Mock private BattleRollRepository battleRollRepository;
    @Mock private BattleLobbyRepository lobbyRepository;
    @Mock private BattleLobbyCaseRepository lobbyCaseRepository;
    @Mock private BattleLobbySlotRepository slotRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private ProgressionService progressionService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BattleLobbyService service;

    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID JOINER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
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
        if (type == SlotType.REAL) {
            s.setLastSeenAt(Instant.now()); // connected by default
        }
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

        LobbyResponse res = service.createLobby(CREATOR, List.of(new CaseSelectionRequest(CASE_ID, 2)), 2);

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

        assertThrows(CategoryLockedException.class,
                () -> service.createLobby(CREATOR, List.of(new CaseSelectionRequest(CASE_ID, 2)), 2));
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    private static CaseDefinition caseDef(String id, int price) {
        CaseDefinition c = new CaseDefinition();
        c.setId(id);
        c.setDisplayName(id);
        c.setPriceVp(price);
        c.setActive(true);
        return c;
    }

    @Test
    void create_multiCase_sumsEntryCostAndRounds() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(caseDefinitionRepository.findById("ghost_basic")).thenReturn(Optional.of(caseDef("ghost_basic", 50)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        stubLobbySave();
        when(walletService.debit(eq(CREATOR), anyLong(), any(), any())).thenReturn(new Wallet());

        LobbyResponse res = service.createLobby(CREATOR,
                List.of(new CaseSelectionRequest(CASE_ID, 3), new CaseSelectionRequest("ghost_basic", 2)), 2);

        assertEquals(400L, res.entryCost()); // 100x3 + 50x2
        assertEquals(5, res.rounds());       // 3 + 2 openings
        assertEquals(2, res.caseSelections().size());
        verify(walletService).debit(eq(CREATOR), eq(400L), any(), eq(LOBBY));
    }

    @Test
    void create_noCases_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createLobby(CREATOR, List.of(), 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_tooManyCases_rejected() {
        List<CaseSelectionRequest> six = List.of(
                new CaseSelectionRequest("c1", 1), new CaseSelectionRequest("c2", 1),
                new CaseSelectionRequest("c3", 1), new CaseSelectionRequest("c4", 1),
                new CaseSelectionRequest("c5", 1), new CaseSelectionRequest("c6", 1));

        ApiException ex = assertThrows(ApiException.class, () -> service.createLobby(CREATOR, six, 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_quantityOutOfRange_rejected() {
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.createLobby(CREATOR, List.of(new CaseSelectionRequest(CASE_ID, 6)), 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_duplicateCases_rejected() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.createLobby(CREATOR,
                List.of(new CaseSelectionRequest(CASE_ID, 1), new CaseSelectionRequest(CASE_ID, 2)), 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void join_doesNotLevelLockJoiner() {
        BattleLobby lobby = waitingLobby(3);
        BattleLobbySlot empty = slot(1, SlotType.EMPTY, null, false);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 1)));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true), empty, slot(2, SlotType.EMPTY, null, false)));
        when(walletService.debit(eq(JOINER), eq(200L), any(), eq(LOBBY))).thenReturn(new Wallet());

        service.joinLobby(JOINER, LOBBY, 1);

        assertEquals(SlotType.REAL, empty.getSlotType());
        assertEquals(JOINER, empty.getAccountId());
        verify(progressionService, never()).isCategoryUnlocked(anyInt(), any(CaseCategory.class));
    }

    @Test
    void join_ownLobby_rejected() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(CREATOR, LOBBY, 1));
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

        // Connected winner (slot seen "now") polls for the result.
        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        assertEquals(Integer.valueOf(0), res.winnerSlotIndex());
        // 2 slots x 2 rounds = 4 rolled skins, all granted to the connected winner.
        verify(inventoryService, times(4)).addItem(eq(CREATOR), eq("skin_a"), any(), any());
    }

    @Test
    void getLobby_resolve_whenBucketsPresent_usesRarityFirst_notFlatPool() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(2);
        lobby.setMaxSlots(2);
        lobby.setEntryCost(200L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS));

        List<BattleLobbySlot> slots = List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.BOT, null, false));

        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(slots);
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry()));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin()));
        when(caseRarityRoll.activeBuckets(any(), any(), any()))
                .thenReturn(List.of(new RarityBucket("Select", 70.0, 1.0, List.of(entry()))));
        when(caseRarityRoll.select(any())).thenReturn(entry());
        when(battleResolver.winningIndex(any())).thenReturn(1); // bot wins; no grants needed
        when(battleRepository.saveAndFlush(any(Battle.class))).thenAnswer(inv -> {
            Battle b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(battleParticipantRepository.findByBattleIdOrderByParticipantIndexAsc(any())).thenReturn(List.of());
        when(battleRollRepository.findByBattleId(any())).thenReturn(List.of());

        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        verify(caseRarityRoll, atLeastOnce()).select(any());
        verify(dropSelector, never()).selectWeighted(any());
    }

    @Test
    void getLobby_full_andDue_disconnectedWinner_getsNoReward() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(2);
        lobby.setMaxSlots(2);
        lobby.setEntryCost(200L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS)); // due

        BattleLobbySlot winner = slot(0, SlotType.REAL, CREATOR, true);
        winner.setLastSeenAt(Instant.now().minus(2, ChronoUnit.MINUTES)); // disconnected
        List<BattleLobbySlot> slots = List.of(winner, slot(1, SlotType.BOT, null, false));

        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(slots);
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry()));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin()));
        when(dropSelector.selectWeighted(any())).thenReturn(entry());
        when(battleResolver.winningIndex(any())).thenReturn(0); // disconnected real player wins
        when(battleRepository.saveAndFlush(any(Battle.class))).thenAnswer(inv -> {
            Battle b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(battleParticipantRepository.findByBattleIdOrderByParticipantIndexAsc(any())).thenReturn(List.of());
        when(battleRollRepository.findByBattleId(any())).thenReturn(List.of());

        // A different account polls (not the winner), so no heartbeat re-connects the winner.
        LobbyResponse res = service.getLobby(JOINER, LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        assertEquals(Integer.valueOf(0), res.winnerSlotIndex());
        // Winner is disconnected: no inventory reward and no BATTLE_WON event.
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void cancelStaleLobby_refundsHost_andCancels() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES)); // stale

        BattleLobbySlot creatorSlot = slot(0, SlotType.REAL, CREATOR, true);
        creatorSlot.setChargedVp(200L);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(creatorSlot, slot(1, SlotType.EMPTY, null, false)));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.CANCELLED, lobby.getStatus());
        verify(walletService).credit(eq(CREATOR), eq(200L), any(), eq(LOBBY));
    }

    @Test
    void cancelStaleLobby_notStale_isNoOp() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now()); // fresh, not stale
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.WAITING, lobby.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void cancelStaleLobby_alreadyCompleted_doesNotRefund() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.COMPLETED);
        lobby.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.COMPLETED, lobby.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void cancelStaleLobby_afterTimeout_cancelsAndRefundsAllRealParticipants() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(3, ChronoUnit.MINUTES)); // past 2-minute timeout

        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false),
                slot(2, SlotType.EMPTY, null, false)));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.CANCELLED, lobby.getStatus());
        verify(walletService).credit(eq(CREATOR), eq(200L), any(), eq(LOBBY));
        verify(walletService).credit(eq(JOINER), eq(200L), any(), eq(LOBBY));
    }

    @Test
    void cancelStaleLobby_beforeTimeout_remainsActive() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(90, ChronoUnit.SECONDS)); // under 2 minutes
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.WAITING, lobby.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void listOpenLobbies_excludesExpired_keepsFresh() {
        BattleLobby expired = new BattleLobby();
        expired.setId(UUID.randomUUID());
        expired.setCreatorAccountId(CREATOR);
        expired.setCaseId(CASE_ID);
        expired.setStatus(LobbyStatus.WAITING);
        expired.setCreatedAt(Instant.now().minus(3, ChronoUnit.MINUTES)); // expired

        BattleLobby fresh = new BattleLobby();
        fresh.setId(UUID.randomUUID());
        fresh.setCreatorAccountId(CREATOR);
        fresh.setCaseId(CASE_ID);
        fresh.setStatus(LobbyStatus.WAITING);
        fresh.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS)); // still valid, creator-only

        when(lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING))
                .thenReturn(List.of(expired, fresh));
        when(caseDefinitionRepository.findAllById(any())).thenReturn(List.of(caseDef(100)));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(fresh.getId()))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), slot(1, SlotType.EMPTY, null, false)));

        List<LobbyResponse> out = service.listOpenLobbies(CREATOR);

        assertEquals(1, out.size());
        assertEquals(fresh.getId().toString(), out.get(0).battleId());
    }

    private BattleLobby startingLobby(int rounds) {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(rounds);
        lobby.setMaxSlots(2);
        lobby.setEntryCost(200L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        return lobby;
    }

    private void stubResolve() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(List.of(entry()));
        when(skinRepository.findAllById(any())).thenReturn(List.of(skin()));
        when(dropSelector.selectWeighted(any())).thenReturn(entry());
        when(battleRepository.saveAndFlush(any(Battle.class))).thenAnswer(inv -> {
            Battle b = inv.getArgument(0);
            b.setId(UUID.randomUUID());
            return b;
        });
        when(battleParticipantRepository.findByBattleIdOrderByParticipantIndexAsc(any())).thenReturn(List.of());
        when(battleRollRepository.findByBattleId(any())).thenReturn(List.of());
        when(inventoryService.addItem(eq(CREATOR), eq("skin_a"), any(), any())).thenAnswer(inv -> {
            InventoryItem item = new InventoryItem();
            item.setId(UUID.randomUUID());
            return item;
        });
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 1)));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 1)));
    }

    @Test
    void pvpCompletion_grantsFiveXpPerRealPlayer_regardlessOfRounds() {
        BattleLobby lobby = startingLobby(5);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false)));
        stubResolve();
        when(battleResolver.winningIndex(any())).thenReturn(0);

        service.getLobby(CREATOR, LOBBY);

        verify(progressionService).grantCaseOpenXp(argThat(a -> CREATOR.equals(a.getId())), eq(5));
        verify(progressionService).grantCaseOpenXp(argThat(a -> JOINER.equals(a.getId())), eq(5));
    }

    @Test
    void repeatedPolling_doesNotGrantXpTwice() {
        BattleLobby lobby = startingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false)));
        stubResolve();
        when(battleResolver.winningIndex(any())).thenReturn(0);

        service.getLobby(CREATOR, LOBBY);
        service.getLobby(CREATOR, LOBBY);

        verify(progressionService, times(2)).grantCaseOpenXp(any(), eq(5));
    }

    @Test
    void loserReceivesNoWinnerRewards() {
        BattleLobby lobby = startingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false)));
        stubResolve();
        when(battleResolver.winningIndex(any())).thenReturn(0);

        service.getLobby(CREATOR, LOBBY);

        verify(inventoryService, times(4)).addItem(eq(CREATOR), eq("skin_a"), any(), any());
        verify(inventoryService, never()).addItem(eq(JOINER), any(), any(), any());
    }

    @Test
    void repeatedPoll_doesNotDuplicateInventory() {
        BattleLobby lobby = startingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false)));
        stubResolve();
        when(battleResolver.winningIndex(any())).thenReturn(0);

        service.getLobby(CREATOR, LOBBY);
        service.getLobby(CREATOR, LOBBY);

        verify(inventoryService, times(4)).addItem(eq(CREATOR), eq("skin_a"), any(), any());
    }

    @Test
    void completedLobby_excludedFromActiveList() {
        when(lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING)).thenReturn(List.of());

        assertTrue(service.listOpenLobbies(CREATOR).isEmpty());
        verify(lobbyRepository).findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING);
    }

    @Test
    void completedLobby_cannotJoin() {
        BattleLobby lobby = startingLobby(2);
        lobby.setStatus(LobbyStatus.COMPLETED);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(JOINER, LOBBY, 1));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void cancelledLobby_cannotJoin() {
        BattleLobby lobby = startingLobby(2);
        lobby.setStatus(LobbyStatus.CANCELLED);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(JOINER, LOBBY, 1));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void completedLobby_cannotCancel() {
        BattleLobby lobby = startingLobby(2);
        lobby.setStatus(LobbyStatus.COMPLETED);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.leaveLobby(JOINER, LOBBY));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void completedLobby_cannotAddBot() {
        BattleLobby lobby = startingLobby(2);
        lobby.setStatus(LobbyStatus.COMPLETED);
        lobby.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        ApiException ex = assertThrows(ApiException.class, () -> service.addBot(CREATOR, LOBBY));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void cancelledLobby_grantsNoXp() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));

        BattleLobbySlot creatorSlot = slot(0, SlotType.REAL, CREATOR, true);
        creatorSlot.setChargedVp(200L);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(creatorSlot, slot(1, SlotType.EMPTY, null, false)));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.CANCELLED, lobby.getStatus());
        verify(progressionService, never()).grantCaseOpenXp(any(), anyInt());
    }

    private BattleLobby waitingLobby(int maxSlots) {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(CREATOR);
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(2);
        lobby.setMaxSlots(maxSlots);
        lobby.setEntryCost(200L);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        return lobby;
    }

    @Test
    void viewingLobby_doesNotChargeOrCreateSlot() {
        BattleLobby lobby = waitingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), slot(1, SlotType.EMPTY, null, false)));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.getLobby(JOINER, LOBBY);

        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        verify(slotRepository, never()).save(any());
    }

    @Test
    void join_specificSlot_chargesVpAndFillsThatSlot() {
        BattleLobby lobby = waitingLobby(3);
        BattleLobbySlot empty = slot(1, SlotType.EMPTY, null, false);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true), empty, slot(2, SlotType.EMPTY, null, false)));
        when(walletService.debit(eq(JOINER), eq(200L), any(), eq(LOBBY))).thenReturn(new Wallet());
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.joinLobby(JOINER, LOBBY, 1);

        verify(walletService).debit(eq(JOINER), eq(200L), any(), eq(LOBBY));
        assertEquals(SlotType.REAL, empty.getSlotType());
        assertEquals(JOINER, empty.getAccountId());
    }

    @Test
    void join_takenSlot_rejected_noCharge() {
        BattleLobby lobby = waitingLobby(3);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, OTHER, false),
                slot(2, SlotType.EMPTY, null, false)));

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(JOINER, LOBBY, 1));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void join_alreadyJoined_notChargedTwice() {
        BattleLobby lobby = waitingLobby(3);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.existsByLobbyIdAndAccountId(LOBBY, JOINER)).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.joinLobby(JOINER, LOBBY, 1));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void leave_viewerWithoutSeat_noMutation() {
        BattleLobby lobby = waitingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), slot(1, SlotType.EMPTY, null, false)));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.leaveLobby(JOINER, LOBBY);

        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(slotRepository, never()).save(any());
    }

    @Test
    void leave_joinedPlayer_freesSlotAndRefundsOnce() {
        BattleLobby lobby = waitingLobby(3);
        BattleLobbySlot joinerSlot = slot(1, SlotType.REAL, JOINER, false);
        joinerSlot.setChargedVp(200L);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true), joinerSlot, slot(2, SlotType.EMPTY, null, false)));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.leaveLobby(JOINER, LOBBY);

        verify(walletService).credit(eq(JOINER), eq(200L), any(), eq(LOBBY));
        assertEquals(SlotType.EMPTY, joinerSlot.getSlotType());
    }

    @Test
    void addBotAllowed_falseForNonHost_trueForHost() {
        BattleLobby lobby = waitingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), slot(1, SlotType.EMPTY, null, false)));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        LobbyResponse asNonHost = service.getLobby(JOINER, LOBBY);
        assertFalse(asNonHost.addBotAvailable());
        assertFalse(asNonHost.slots().get(1).addBotAllowed());

        LobbyResponse asHost = service.getLobby(CREATOR, LOBBY);
        assertTrue(asHost.addBotAvailable());
        assertTrue(asHost.slots().get(1).addBotAllowed());
    }

    @Test
    void host_canAddBot_afterDelay() {
        BattleLobby lobby = waitingLobby(2);
        BattleLobbySlot empty = slot(1, SlotType.EMPTY, null, false);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), empty));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.addBot(CREATOR, LOBBY);

        assertEquals(SlotType.BOT, empty.getSlotType());
    }

    @Test
    void addBot_doesNotOverwriteRealPlayer_fillsOnlyEmptySlot() {
        BattleLobby lobby = waitingLobby(3);
        BattleLobbySlot seatedReal = slot(1, SlotType.REAL, JOINER, false);
        BattleLobbySlot empty = slot(2, SlotType.EMPTY, null, false);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), seatedReal, empty));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        service.addBot(CREATOR, LOBBY);

        // The seated real player is untouched; the bot lands in the only EMPTY slot.
        assertEquals(SlotType.REAL, seatedReal.getSlotType());
        assertEquals(JOINER, seatedReal.getAccountId());
        assertEquals(SlotType.BOT, empty.getSlotType());
    }

    @Test
    void viewer_doesNotStartBattle_whileEmptySlotRemains() {
        BattleLobby lobby = waitingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true), slot(1, SlotType.EMPTY, null, false)));
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));

        // A non-seated viewer polling an unfilled lobby must not trigger a start.
        LobbyResponse res = service.getLobby(JOINER, LOBBY);

        assertEquals(LobbyStatus.WAITING.name(), res.status());
        assertEquals(LobbyStatus.WAITING, lobby.getStatus());
        verify(battleRepository, never()).saveAndFlush(any(Battle.class));
    }

    @Test
    void viewer_getsNoRewardOrXp_whenBattleStartsWithoutThem() {
        BattleLobby lobby = startingLobby(2);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        // Only the host (real) and a bot fill the slots; OTHER is merely a viewer.
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.BOT, null, false)));
        stubResolve();
        when(battleResolver.winningIndex(any())).thenReturn(0);

        // The viewer (not in any slot) polls and triggers the due resolution.
        LobbyResponse res = service.getLobby(OTHER, LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        // Viewer is not a participant: no inventory reward and no XP for them.
        verify(inventoryService, never()).addItem(eq(OTHER), any(), any(), any());
        verify(progressionService, never()).grantCaseOpenXp(argThat(a -> OTHER.equals(a.getId())), anyInt());
    }

    // --- Free Lobby Event ------------------------------------------------------

    private void stubEventCases() {
        when(caseDefinitionRepository.findById("classic_basic")).thenReturn(Optional.of(caseDef("classic_basic", 1150)));
        when(caseDefinitionRepository.findById("ghost_basic")).thenReturn(Optional.of(caseDef("ghost_basic", 1150)));
        when(caseDefinitionRepository.findById("bulldog_basic")).thenReturn(Optional.of(caseDef("bulldog_basic", 1150)));
        when(caseDefinitionRepository.findById("vandal_basic")).thenReturn(Optional.of(caseDef("vandal_basic", 1150)));
        when(caseDefinitionRepository.findById("melee_basic")).thenReturn(Optional.of(caseDef("melee_basic", 1150)));
    }

    private BattleLobby eventLobby() {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(BattleLobbyService.SYSTEM_EVENT_ACCOUNT_ID);
        lobby.setCaseId("classic_basic");
        lobby.setRounds(25);
        lobby.setMaxSlots(BattleLobbyService.EVENT_LOBBY_SLOTS);
        lobby.setEntryCost(0L);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setCreatedAt(Instant.now().minus(10, ChronoUnit.SECONDS));
        lobby.setEvent(true);
        lobby.setEventWindowKey("free-event-123");
        return lobby;
    }

    @Test
    void createEventLobby_createsOneFreeSystemLobby_withNoRealHost_andNoCharge() {
        stubEventCases();
        when(lobbyRepository.existsByEventWindowKey(any())).thenReturn(false);
        when(lobbyRepository.saveAndFlush(any(BattleLobby.class))).thenAnswer(inv -> {
            BattleLobby l = inv.getArgument(0);
            l.setId(LOBBY);
            return l;
        });
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        Optional<UUID> created = service.createEventLobby();

        assertTrue(created.isPresent());
        verify(lobbyRepository).saveAndFlush(argThat(l ->
                l.isEvent()
                && l.getEntryCost() == 0L
                && BattleLobbyService.SYSTEM_EVENT_ACCOUNT_ID.equals(l.getCreatorAccountId())
                && l.getRounds() == 25
                && l.getEventWindowKey() != null));
        verify(walletService, never()).debit(any(), anyLong(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BattleLobbySlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(slotRepository).saveAll(slotsCaptor.capture());
        List<BattleLobbySlot> slots = slotsCaptor.getValue();
        assertEquals(BattleLobbyService.EVENT_LOBBY_SLOTS, slots.size());
        assertTrue(slots.stream().allMatch(s -> s.getSlotType() == SlotType.EMPTY));
        assertTrue(slots.stream().noneMatch(BattleLobbySlot::isCreator));
        assertTrue(slots.stream().allMatch(s -> s.getChargedVp() == 0L));
    }

    @Test
    void createEventLobby_skipsWhenWindowAlreadyHasOne() {
        when(lobbyRepository.existsByEventWindowKey(any())).thenReturn(true);

        Optional<UUID> created = service.createEventLobby();

        assertTrue(created.isEmpty());
        verify(lobbyRepository, never()).saveAndFlush(any());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
    }

    @Test
    void eventLobby_appearsInList_withFreeMarker_andZeroCost() {
        BattleLobby ev = eventLobby();
        when(lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING)).thenReturn(List.of(ev));
        when(lobbyCaseRepository.findByLobbyIdInOrderByOrdinalAsc(any())).thenReturn(List.of());
        when(caseDefinitionRepository.findAllById(any())).thenReturn(List.of(caseDef("classic_basic", 1150)));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(ev.getId()))
                .thenReturn(List.of(slot(0, SlotType.EMPTY, null, false), slot(1, SlotType.EMPTY, null, false)));

        List<LobbyResponse> out = service.listOpenLobbies(JOINER);

        assertEquals(1, out.size());
        assertTrue(out.get(0).isEventLobby());
        assertEquals(BattleLobbyService.EVENT_TYPE_FREE, out.get(0).eventType());
        assertEquals(0L, out.get(0).entryCost());
    }

    @Test
    void joinEventLobby_doesNotDebitWallet_andFillsSlotFree() {
        BattleLobby ev = eventLobby();
        BattleLobbySlot empty0 = slot(0, SlotType.EMPTY, null, false);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(ev));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 1)));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(empty0, slot(1, SlotType.EMPTY, null, false)));
        when(lobbyCaseRepository.findByLobbyIdOrderByOrdinalAsc(LOBBY)).thenReturn(List.of());
        when(caseDefinitionRepository.findAllById(any())).thenReturn(List.of(caseDef("classic_basic", 1150)));

        service.joinLobby(JOINER, LOBBY, 0);

        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        assertEquals(SlotType.REAL, empty0.getSlotType());
        assertEquals(JOINER, empty0.getAccountId());
        assertEquals(0L, empty0.getChargedVp());
    }

    @Test
    void normalCreate_isNeverAnEventLobby() {
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        stubLobbySave();
        when(walletService.debit(eq(CREATOR), eq(200L), any(), any())).thenReturn(new Wallet());

        LobbyResponse res = service.createLobby(CREATOR, List.of(new CaseSelectionRequest(CASE_ID, 2)), 2);

        assertFalse(res.isEventLobby());
        assertNull(res.eventType());
        assertEquals(200L, res.entryCost());
    }

    @Test
    void eventWindowKey_isStableWithinWindow_andChangesAcrossWindows() {
        Instant inWindow = Instant.ofEpochSecond(960);
        Instant sameWindow = Instant.ofEpochSecond(1079);
        Instant nextWindow = Instant.ofEpochSecond(1080);

        assertEquals(
                BattleLobbyService.currentEventWindowKey(inWindow),
                BattleLobbyService.currentEventWindowKey(sameWindow));
        assertNotEquals(
                BattleLobbyService.currentEventWindowKey(inWindow),
                BattleLobbyService.currentEventWindowKey(nextWindow));
    }
}
