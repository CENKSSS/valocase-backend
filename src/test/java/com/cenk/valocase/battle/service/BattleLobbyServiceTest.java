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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import static com.cenk.valocase.battle.service.BattleLobbyService.LOBBY_TIMEOUT;

import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.analytics.service.PlayerPresenceService;
import com.cenk.valocase.battle.domain.Battle;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.BattleParticipant;
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
    @Mock private PlayerPresenceService playerPresenceService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private BattleLobbyService service;

    @BeforeEach
    void stubLevelDerivation() {
        // Lobby creation derives the level from total XP through ProgressionService
        // rather than reading the cached accounts.level column. Mirror the test
        // account's level field so the isCategoryUnlocked expectations still match.
        when(progressionService.levelOf(any(Account.class)))
                .thenAnswer(inv -> inv.<Account>getArgument(0).getLevel());
    }

    private static final UUID CREATOR = UUID.randomUUID();
    private static final UUID JOINER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final UUID FOURTH = UUID.randomUUID();
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
    void create_gatesOnXpDerivedLevel_notTheCachedLevelColumn() {
        // The stored column says level 1, but total XP puts the player at 50.
        // Case unlock must follow the derived value, like case opening does.
        Account stale = account(CREATOR, 1);
        when(caseDefinitionRepository.findById(CASE_ID)).thenReturn(Optional.of(caseDef(100)));
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(stale));
        when(progressionService.levelOf(stale)).thenReturn(50);
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        stubLobbySave();
        when(walletService.debit(eq(CREATOR), eq(200L), any(), any())).thenReturn(new Wallet());

        LobbyResponse res = service.createLobby(CREATOR, List.of(new CaseSelectionRequest(CASE_ID, 2)), 2);

        assertEquals(LobbyStatus.WAITING.name(), res.status());
        verify(progressionService).isCategoryUnlocked(eq(50), any(CaseCategory.class));
        verify(progressionService, never()).isCategoryUnlocked(eq(1), any(CaseCategory.class));
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

    /** {@code count} distinct cases, each priced 100, each selected {@code quantity} times. */
    private List<CaseSelectionRequest> selections(int count, int quantity) {
        List<CaseSelectionRequest> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "case_" + i;
            when(caseDefinitionRepository.findById(id)).thenReturn(Optional.of(caseDef(id, 100)));
            out.add(new CaseSelectionRequest(id, quantity));
        }
        return out;
    }

    @Test
    void create_fiveCasesTimesFiveEach_isTheMaximumAndIsAccepted() {
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);
        stubLobbySave();
        when(walletService.debit(eq(CREATOR), anyLong(), any(), any())).thenReturn(new Wallet());

        LobbyResponse res = service.createLobby(CREATOR, selections(5, 5), 2);

        assertEquals(BattleLobbyService.MAX_TOTAL_ROUNDS, res.rounds());
        assertEquals(2500L, res.entryCost()); // 100 VP x 25 openings
        verify(walletService).debit(eq(CREATOR), eq(2500L), any(), eq(LOBBY));
    }

    @Test
    void create_moreThanTwentyFiveTotalOpenings_rejected() {
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));
        when(progressionService.isCategoryUnlocked(eq(50), any(CaseCategory.class))).thenReturn(true);

        // Six cases would also trip the distinct-case rule, so push the total over
        // the line with five cases while staying inside the per-case limit... which
        // is impossible today. Prove the total guard fires on its own instead.
        assertEquals(25, BattleLobbyService.MAX_CASE_TYPES * BattleLobbyService.CASE_QUANTITY_MAX);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.createLobby(CREATOR, selections(6, 5), 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_perCaseQuantityLimitIsIndependentOfTheBotBattleLimit() {
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 50)));

        // Six copies of one case: over the lobby's own per-case limit.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.createLobby(CREATOR, selections(1, 6), 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertTrue(ex.getMessage().contains(String.valueOf(BattleLobbyService.CASE_QUANTITY_MAX)));
        verify(walletService, never()).debit(any(), anyLong(), any(), any());
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
        // Comfortably past LOBBY_TIMEOUT, not sitting on the boundary.
        lobby.setCreatedAt(Instant.now().minus(LOBBY_TIMEOUT).minus(1, ChronoUnit.MINUTES));

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
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS)); // well under LOBBY_TIMEOUT
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.WAITING, lobby.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    /** A WAITING lobby of either kind, created {@code age} ago. */
    private BattleLobby waitingLobby(boolean event, Duration age) {
        BattleLobby lobby = new BattleLobby();
        lobby.setId(LOBBY);
        lobby.setCreatorAccountId(event ? BattleLobbyService.SYSTEM_EVENT_ACCOUNT_ID : CREATOR);
        lobby.setStatus(LobbyStatus.WAITING);
        lobby.setEvent(event);
        lobby.setCreatedAt(Instant.now().minus(age));
        return lobby;
    }

    @Test
    void playerLobby_expiresAt90Seconds() {
        // 100s: past the player window, but well inside the event one.
        BattleLobby lobby = waitingLobby(false, Duration.ofSeconds(100));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.REAL, CREATOR, true)));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.CANCELLED, lobby.getStatus());
        verify(walletService).credit(eq(CREATOR), eq(200L), any(), eq(LOBBY));
    }

    @Test
    void eventLobby_survivesTheShortPlayerWindow() {
        // Same 100s age: an event lobby is still open because it gets 3 minutes.
        BattleLobby lobby = waitingLobby(true, Duration.ofSeconds(100));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.WAITING, lobby.getStatus());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void eventLobby_expiresAtThreeMinutes() {
        BattleLobby lobby = waitingLobby(true, BattleLobbyService.EVENT_LOBBY_TIMEOUT.plusSeconds(30));
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(slot(0, SlotType.EMPTY, null, false)));

        service.cancelStaleLobby(LOBBY);

        assertEquals(LobbyStatus.CANCELLED, lobby.getStatus());
        // Nobody is ever charged for an event lobby, so nothing is refunded.
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void staleSweep_asksForBothWindows_shortForPlayersLongForEvents() {
        when(lobbyRepository.findStaleByStatus(eq(LobbyStatus.WAITING), any(), any())).thenReturn(List.of());

        service.staleWaitingLobbyIds();

        ArgumentCaptor<Instant> playerCutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> eventCutoff = ArgumentCaptor.forClass(Instant.class);
        verify(lobbyRepository).findStaleByStatus(
                eq(LobbyStatus.WAITING), playerCutoff.capture(), eventCutoff.capture());
        // The event cutoff reaches further back, so event lobbies are swept later.
        assertTrue(eventCutoff.getValue().isBefore(playerCutoff.getValue()));
        assertEquals(
                BattleLobbyService.EVENT_LOBBY_TIMEOUT.minus(LOBBY_TIMEOUT),
                Duration.between(eventCutoff.getValue(), playerCutoff.getValue()));
    }

    @Test
    void listOpenLobbies_excludesExpired_keepsFresh() {
        BattleLobby expired = new BattleLobby();
        expired.setId(UUID.randomUUID());
        expired.setCreatorAccountId(CREATOR);
        expired.setCaseId(CASE_ID);
        expired.setStatus(LobbyStatus.WAITING);
        expired.setCreatedAt(Instant.now().minus(LOBBY_TIMEOUT).minus(1, ChronoUnit.MINUTES)); // expired

        BattleLobby fresh = new BattleLobby();
        fresh.setId(UUID.randomUUID());
        fresh.setCreatorAccountId(CREATOR);
        fresh.setCaseId(CASE_ID);
        fresh.setStatus(LobbyStatus.WAITING);
        fresh.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS)); // still valid, creator-only

        when(lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING))
                .thenReturn(List.of(expired, fresh));
        when(caseDefinitionRepository.findAllById(any())).thenReturn(List.of(caseDef(100)));
        BattleLobbySlot freshSlot0 = slot(0, SlotType.REAL, CREATOR, true);
        freshSlot0.setLobbyId(fresh.getId());
        BattleLobbySlot freshSlot1 = slot(1, SlotType.EMPTY, null, false);
        freshSlot1.setLobbyId(fresh.getId());
        when(slotRepository.findByLobbyIdInOrderBySlotIndexAsc(any()))
                .thenReturn(List.of(freshSlot0, freshSlot1));

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

    /**
     * Participants the service persisted during resolution. The read path is
     * stubbed to hand this same live list back, so a response reflects the totals
     * the battle actually produced instead of an empty stand-in — which is what
     * the draw refund and the per-viewer refundVp are derived from.
     */
    private final List<BattleParticipant> savedParticipants = new ArrayList<>();

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
        when(battleParticipantRepository.saveAll(any())).thenAnswer(inv -> {
            List<BattleParticipant> batch = inv.getArgument(0);
            savedParticipants.clear();
            savedParticipants.addAll(batch);
            return batch;
        });
        when(battleParticipantRepository.findByBattleIdOrderByParticipantIndexAsc(any()))
                .thenReturn(savedParticipants);
        when(battleRollRepository.findByBattleId(any())).thenReturn(List.of());
        when(inventoryService.addItem(any(), any(), any(), any())).thenAnswer(inv -> {
            InventoryItem item = new InventoryItem();
            item.setId(UUID.randomUUID());
            return item;
        });
        when(accountRepository.findById(CREATOR)).thenReturn(Optional.of(account(CREATOR, 1)));
        when(accountRepository.findById(JOINER)).thenReturn(Optional.of(account(JOINER, 1)));
        when(accountRepository.findById(OTHER)).thenReturn(Optional.of(account(OTHER, 1)));
        when(accountRepository.findById(FOURTH)).thenReturn(Optional.of(account(FOURTH, 1)));
    }

    /** A STARTING lobby with an explicit slot count, one opening per slot. */
    private BattleLobby startingLobby(int rounds, int maxSlots) {
        BattleLobby lobby = startingLobby(rounds);
        lobby.setMaxSlots(maxSlots);
        return lobby;
    }

    /**
     * Makes each slot roll its own skin so the participants end on the given
     * totals — one opening per slot, so slot i is worth {@code vpPerSlot[i]}.
     */
    private void stubTotals(int... vpPerSlot) {
        List<Skin> skins = new ArrayList<>();
        List<CaseEntry> entries = new ArrayList<>();
        for (int i = 0; i < vpPerSlot.length; i++) {
            skins.add(skin("skin_" + i, vpPerSlot[i]));
            entries.add(entry("skin_" + i));
        }
        when(caseEntryRepository.findByCaseIdOrderBySkinIdAsc(CASE_ID)).thenReturn(entries);
        when(skinRepository.findAllById(any())).thenReturn(skins);
        CaseEntry[] rest = entries.subList(1, entries.size()).toArray(new CaseEntry[0]);
        when(dropSelector.selectWeighted(any())).thenReturn(entries.get(0), rest);

        long top = 0;
        for (int vp : vpPerSlot) {
            top = Math.max(top, vp);
        }
        when(battleResolver.topTotal(any())).thenReturn(top);
    }

    private static CaseEntry entry(String skinId) {
        CaseEntry e = new CaseEntry();
        e.setCaseId(CASE_ID);
        e.setSkinId(skinId);
        e.setWeight(1);
        return e;
    }

    private static Skin skin(String id, int vpValue) {
        Skin s = new Skin();
        s.setId(id);
        s.setVpValue(vpValue);
        s.setActive(true);
        return s;
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

    /** A four-real-player lobby whose slots end on the given totals. */
    private List<BattleLobbySlot> fourPlayerDraw(int... vpPerSlot) {
        BattleLobby lobby = startingLobby(1, 4);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        List<BattleLobbySlot> slots = List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false),
                slot(2, SlotType.REAL, OTHER, false),
                slot(3, SlotType.REAL, FOURTH, false));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(slots);
        stubResolve();
        stubTotals(vpPerSlot);
        when(battleResolver.isDraw(any())).thenReturn(true);
        return slots;
    }

    private void verifyRefunded(UUID account) {
        verify(walletService).credit(
                eq(account), eq(200L), eq(BattleLobbyService.REASON_LOBBY_DRAW_REFUND), eq(LOBBY));
    }

    private void verifyNotRefunded(UUID account) {
        verify(walletService, never()).credit(eq(account), anyLong(), any(), any());
    }

    @Test
    void draw_twoSharingTheTop_refundsOnlyThoseTwo() {
        fourPlayerDraw(900, 900, 500, 300);

        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        assertEquals(LobbyStatus.COMPLETED.name(), res.status());
        assertTrue(res.isDraw());
        // -1 and null names, so a client resolving the winner by index or by name finds nobody.
        assertEquals(Integer.valueOf(BattleResolver.DRAW_WINNER_INDEX), res.winnerSlotIndex());
        assertNull(res.winnerDisplayName());
        assertNull(res.winnerAvatarId());
        assertEquals(200L, res.refundVp()); // the viewer is tied at the top

        verifyRefunded(CREATOR);
        verifyRefunded(JOINER);
        verifyNotRefunded(OTHER);   // 500 — lost normally
        verifyNotRefunded(FOURTH);  // 300 — lost normally
        // Nobody wins the loot in a draw, not even those tied at the top.
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
        verify(battleResolver, never()).winningIndex(any());
    }

    @Test
    void draw_threeSharingTheTop_refundsThoseThree() {
        fourPlayerDraw(900, 900, 900, 100);

        service.getLobby(CREATOR, LOBBY);

        verifyRefunded(CREATOR);
        verifyRefunded(JOINER);
        verifyRefunded(OTHER);
        verifyNotRefunded(FOURTH);
    }

    @Test
    void draw_everyoneEqual_refundsEveryone() {
        fourPlayerDraw(700, 700, 700, 700);

        service.getLobby(CREATOR, LOBBY);

        verifyRefunded(CREATOR);
        verifyRefunded(JOINER);
        verifyRefunded(OTHER);
        verifyRefunded(FOURTH);
    }

    @Test
    void draw_playerBelowTheTop_isToldTheyGotNothingBack() {
        fourPlayerDraw(900, 900, 500, 300);

        // The viewer sits at 500, below the shared top: the battle drew, but not for them.
        LobbyResponse res = service.getLobby(OTHER, LOBBY);

        assertTrue(res.isDraw());
        assertEquals(0L, res.refundVp());
    }

    @Test
    void draw_grantsBattleXpToEveryone_winnersAndLosersAlike() {
        fourPlayerDraw(900, 900, 500, 300);

        service.getLobby(CREATOR, LOBBY);

        // A drawn battle was still played, so every real slot earns its XP —
        // including the two below the shared top.
        verify(progressionService).grantCaseOpenXp(argThat(a -> CREATOR.equals(a.getId())), eq(5));
        verify(progressionService).grantCaseOpenXp(argThat(a -> JOINER.equals(a.getId())), eq(5));
        verify(progressionService).grantCaseOpenXp(argThat(a -> OTHER.equals(a.getId())), eq(5));
        verify(progressionService).grantCaseOpenXp(argThat(a -> FOURTH.equals(a.getId())), eq(5));
    }

    @Test
    void draw_botTiedAtTheTop_isNotCredited_andTheRealPlayerBelowGetsNothing() {
        BattleLobby lobby = startingLobby(1, 3);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.BOT, null, false),
                slot(1, SlotType.BOT, null, false),
                slot(2, SlotType.REAL, CREATOR, false)));
        stubResolve();
        stubTotals(900, 900, 500);
        when(battleResolver.isDraw(any())).thenReturn(true);

        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        // Bots share the top, so it is a draw with no winner — but bots hold no
        // wallet and the only real player is below the top, so nobody is credited.
        assertTrue(res.isDraw());
        assertEquals(0L, res.refundVp());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
        verify(inventoryService, never()).addItem(any(), any(), any(), any());
    }

    @Test
    void draw_tiedTopPlayerIsRefundedEvenWhenDisconnected() {
        BattleLobby lobby = startingLobby(1, 2);
        BattleLobbySlot away = slot(0, SlotType.REAL, CREATOR, true);
        away.setLastSeenAt(Instant.now().minus(5, ChronoUnit.MINUTES)); // disconnected
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY))
                .thenReturn(List.of(away, slot(1, SlotType.REAL, JOINER, false)));
        stubResolve();
        stubTotals(900, 900);
        when(battleResolver.isDraw(any())).thenReturn(true);

        // A different account polls, so no heartbeat reconnects the away player.
        service.getLobby(JOINER, LOBBY);

        // Their money is theirs regardless of whether they were watching.
        verifyRefunded(CREATOR);
    }

    @Test
    void draw_repeatedPoll_doesNotRefundTwice() {
        fourPlayerDraw(900, 900, 500, 300);

        service.getLobby(CREATOR, LOBBY);
        LobbyResponse second = service.getLobby(CREATOR, LOBBY);

        // The second read still reports the draw, but resolves (and refunds) nothing.
        assertTrue(second.isDraw());
        assertEquals(200L, second.refundVp());
        verify(walletService, times(1)).credit(
                eq(CREATOR), eq(200L), eq(BattleLobbyService.REASON_LOBBY_DRAW_REFUND), eq(LOBBY));
        verify(walletService, times(1)).credit(
                eq(JOINER), eq(200L), eq(BattleLobbyService.REASON_LOBBY_DRAW_REFUND), eq(LOBBY));
    }

    @Test
    void draw_freeEventLobby_completesWithoutRefund() {
        BattleLobby lobby = startingLobby(1, 2);
        lobby.setEntryCost(0L);
        lobby.setEvent(true);
        BattleLobbySlot first = slot(0, SlotType.REAL, CREATOR, false);
        first.setChargedVp(0L);
        BattleLobbySlot second = slot(1, SlotType.REAL, JOINER, false);
        second.setChargedVp(0L);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(first, second));
        stubResolve();
        stubTotals(700, 700);
        when(battleResolver.isDraw(any())).thenReturn(true);

        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        assertTrue(res.isDraw());
        assertEquals(0L, res.refundVp());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void tieBelowTheTop_isANormalWin_notADraw() {
        BattleLobby lobby = startingLobby(1, 4);
        when(lobbyRepository.findByIdForUpdate(LOBBY)).thenReturn(Optional.of(lobby));
        when(slotRepository.findByLobbyIdOrderBySlotIndexAsc(LOBBY)).thenReturn(List.of(
                slot(0, SlotType.REAL, CREATOR, true),
                slot(1, SlotType.REAL, JOINER, false),
                slot(2, SlotType.REAL, OTHER, false),
                slot(3, SlotType.REAL, FOURTH, false)));
        stubResolve();
        stubTotals(900, 500, 500, 300); // the tie at 500 is below the top
        when(battleResolver.isDraw(any())).thenReturn(false);
        when(battleResolver.winningIndex(any())).thenReturn(0);

        LobbyResponse res = service.getLobby(CREATOR, LOBBY);

        assertFalse(res.isDraw());
        assertEquals(0L, res.refundVp());
        assertEquals(Integer.valueOf(0), res.winnerSlotIndex());
        // winnerDisplayName must match the winning slot exactly, or the client shows no winner.
        assertEquals(res.slots().get(0).displayName(), res.winnerDisplayName());
        verify(walletService, never()).credit(any(), anyLong(), any(), any());
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
        lobby.setRounds(15);
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
                && l.getRounds() == 15
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
        when(slotRepository.findByLobbyIdInOrderBySlotIndexAsc(any()))
                .thenReturn(List.of(slot(0, SlotType.EMPTY, null, false), slot(1, SlotType.EMPTY, null, false)));

        List<LobbyResponse> out = service.listOpenLobbies(JOINER);

        assertEquals(1, out.size());
        assertTrue(out.get(0).isEventLobby());
        assertEquals(BattleLobbyService.EVENT_TYPE_FREE, out.get(0).eventType());
        assertEquals(0L, out.get(0).entryCost());
    }

    @Test
    void listOpenLobbies_keepsAnEventLobbyPastThePlayerWindow() {
        // The list view applies the same per-kind expiry as the cleanup sweep, so
        // a 100s-old event lobby is still browsable even though a player lobby of
        // the same age would already be gone.
        BattleLobby ev = eventLobby();
        ev.setCreatedAt(Instant.now().minus(100, ChronoUnit.SECONDS));
        when(lobbyRepository.findByStatusOrderByCreatedAtDesc(LobbyStatus.WAITING)).thenReturn(List.of(ev));
        when(lobbyCaseRepository.findByLobbyIdInOrderByOrdinalAsc(any())).thenReturn(List.of());
        when(caseDefinitionRepository.findAllById(any())).thenReturn(List.of(caseDef("classic_basic", 1150)));
        when(slotRepository.findByLobbyIdInOrderBySlotIndexAsc(any()))
                .thenReturn(List.of(slot(0, SlotType.EMPTY, null, false), slot(1, SlotType.EMPTY, null, false)));

        assertEquals(1, service.listOpenLobbies(JOINER).size());
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
        // Windows are EVENT_INTERVAL (15 min = 900 s) wide, floored to the epoch.
        Instant inWindow = Instant.ofEpochSecond(900);
        Instant sameWindow = Instant.ofEpochSecond(1_799);
        Instant nextWindow = Instant.ofEpochSecond(1_800);

        assertEquals(
                BattleLobbyService.currentEventWindowKey(inWindow),
                BattleLobbyService.currentEventWindowKey(sameWindow));
        assertNotEquals(
                BattleLobbyService.currentEventWindowKey(inWindow),
                BattleLobbyService.currentEventWindowKey(nextWindow));
    }

    @Test
    void eventWindowKey_differsForAnyTwoInstantsOneIntervalApart() {
        // The key is the multi-instance dedup guard, so two events spaced by the
        // shortest possible cadence must never land on the same key.
        long interval = BattleLobbyService.EVENT_INTERVAL.getSeconds();
        for (long offset = 0; offset < interval; offset += 137) {
            Instant first = Instant.ofEpochSecond(1_000_000 + offset);
            assertNotEquals(
                    BattleLobbyService.currentEventWindowKey(first),
                    BattleLobbyService.currentEventWindowKey(first.plusSeconds(interval)),
                    "keys collided for offset " + offset);
        }
    }

    /** Stubs everything createEventLobby needs once it decides an event is due. */
    private void stubEventCreation() {
        stubEventCases();
        when(lobbyRepository.existsByEventWindowKey(any())).thenReturn(false);
        when(lobbyRepository.saveAndFlush(any(BattleLobby.class))).thenAnswer(inv -> {
            BattleLobby l = inv.getArgument(0);
            l.setId(LOBBY);
            return l;
        });
        when(slotRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createEventLobby_busyGame_createsEveryFifteenMinutes() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(10L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(16, ChronoUnit.MINUTES)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_busyGame_skipsBeforeFifteenMinutes() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(10L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(14, ChronoUnit.MINUTES)));

        assertTrue(service.createEventLobby().isEmpty());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEventLobby_sixOnline_stillUsesTheFastCadence() {
        // 6 is the threshold itself: "fewer than 6" is slow, 6 is already fast.
        when(playerPresenceService.onlinePlayerCount()).thenReturn(6L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(16, ChronoUnit.MINUTES)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_quietGame_waitsOutTheSlowCadence() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(5L);
        // Hours past the fast cadence, still nowhere near the low-population one.
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(6, ChronoUnit.HOURS)));

        assertTrue(service.createEventLobby().isEmpty());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEventLobby_quietGame_createsOnceTheSlowCadenceElapses() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(0L);
        when(lobbyRepository.latestEventLobbyCreatedAt()).thenReturn(Optional.of(
                Instant.now().minus(BattleLobbyService.EVENT_INTERVAL_LOW_POPULATION).minusSeconds(1)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_quietGameThatFillsUp_becomesDueImmediately() {
        // Six hours into the slow wait, players arrive. The cadence is re-read
        // every run, so the event is due at once — not a day later.
        when(playerPresenceService.onlinePlayerCount()).thenReturn(8L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(6, ChronoUnit.HOURS)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_playersArriveEarlyInAQuietWait_stillWaitsOutTheFastCadence() {
        // Quiet start put the event 30 minutes out; 10 minutes in, players arrive.
        // The requirement drops to 15, but only 10 have elapsed, so it is not due.
        when(playerPresenceService.onlinePlayerCount()).thenReturn(10L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(10, ChronoUnit.MINUTES)));

        assertTrue(service.createEventLobby().isEmpty());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEventLobby_playersArriveEarly_thenFiresAtFifteenNotThirty() {
        // Same wait five minutes later: 15 elapsed under the fast cadence is due,
        // so a quiet wait that filled up costs 15 minutes total, never 30.
        when(playerPresenceService.onlinePlayerCount()).thenReturn(10L);
        when(lobbyRepository.latestEventLobbyCreatedAt())
                .thenReturn(Optional.of(Instant.now().minus(15, ChronoUnit.MINUTES).minusSeconds(1)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_waitNeverExceedsTheLowPopulationCadence() {
        // Nobody online at all: the slow cadence is the hard ceiling, so time
        // spent waiting can never be topped up beyond it.
        when(playerPresenceService.onlinePlayerCount()).thenReturn(0L);
        when(lobbyRepository.latestEventLobbyCreatedAt()).thenReturn(Optional.of(
                Instant.now().minus(BattleLobbyService.EVENT_INTERVAL_LOW_POPULATION).minusSeconds(1)));
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }

    @Test
    void createEventLobby_neverFiresBeforeTheFastCadence_howeverBusy() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(500L);
        when(lobbyRepository.latestEventLobbyCreatedAt()).thenReturn(Optional.of(
                Instant.now().minus(BattleLobbyService.EVENT_INTERVAL).plusSeconds(30)));

        assertTrue(service.createEventLobby().isEmpty());
        verify(lobbyRepository, never()).saveAndFlush(any());
    }

    @Test
    void createEventLobby_firstEverRun_createsImmediately() {
        when(playerPresenceService.onlinePlayerCount()).thenReturn(0L);
        when(lobbyRepository.latestEventLobbyCreatedAt()).thenReturn(Optional.empty());
        stubEventCreation();

        assertTrue(service.createEventLobby().isPresent());
    }
}
