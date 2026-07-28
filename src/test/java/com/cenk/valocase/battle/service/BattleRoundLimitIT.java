package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbyCase;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.LobbyStatus;
import com.cenk.valocase.battle.domain.SlotType;
import com.cenk.valocase.battle.dto.CaseSelectionRequest;
import com.cenk.valocase.battle.dto.LobbyResponse;
import com.cenk.valocase.battle.repository.BattleLobbyCaseRepository;
import com.cenk.valocase.battle.repository.BattleLobbyRepository;
import com.cenk.valocase.battle.repository.BattleLobbySlotRepository;
import com.cenk.valocase.caseopening.service.CaseRarityRoll;
import com.cenk.valocase.caseopening.service.DropSelector;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.service.WalletService;

/**
 * The lobby's round limits against a real Flyway-migrated Postgres: 25 openings
 * is accepted, anything past it is refused, and a full 25-round battle actually
 * resolves end to end with every row it implies.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any Postgres and run it.
 */
@SpringBootTest
class BattleRoundLimitIT {

    private static final int CASE_PRICE = 100;
    private static final long STARTING_VP = 100_000L;

    /**
     * The dice are fixed so the two resolution tests below are deterministic:
     * one has to end in a draw, the other has to end with a single winner who
     * then takes all 100 rolls. Everything else — wallet, inventory, rows,
     * transaction — stays real.
     */
    @MockitoBean DropSelector dropSelector;
    @MockitoBean CaseRarityRoll caseRarityRoll;

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired WalletService walletService;
    @Autowired BattleLobbyRepository lobbyRepository;
    @Autowired BattleLobbyCaseRepository lobbyCaseRepository;
    @Autowired BattleLobbySlotRepository slotRepository;
    @Autowired BattleLobbyService lobbyService;

    /** Six cheap, unlocked cases so a selection can legally reach — and exceed — the cap. */
    @BeforeEach
    void seedCatalog() {
        for (String[] skin : new String[][]{{"rl_skin", "250"}, {"rl_skin_hi", "1000"}}) {
            jdbc.update("""
                    INSERT INTO skins (id, display_name, weapon, rarity, vp_value, active)
                    VALUES (?, ?, 'Classic', 'Select', CAST(? AS INTEGER), TRUE)
                    ON CONFLICT (id) DO NOTHING
                    """, skin[0], "Round Limit " + skin[0], skin[1]);
        }
        for (int i = 0; i < 6; i++) {
            String caseId = caseId(i);
            jdbc.update("""
                    INSERT INTO case_definitions (id, display_name, price_vp, active)
                    VALUES (?, ?, ?, TRUE) ON CONFLICT (id) DO NOTHING
                    """, caseId, "Round Limit " + i, CASE_PRICE);
            for (String skinId : new String[]{"rl_skin", "rl_skin_hi"}) {
                jdbc.update("""
                        INSERT INTO case_entries (id, case_id, skin_id, weight)
                        VALUES (gen_random_uuid(), ?, ?, 1)
                        ON CONFLICT ON CONSTRAINT uq_case_entries_case_skin DO NOTHING
                        """, caseId, skinId);
            }
        }
        // Flat pool path, and every slot rolls the same 250 VP skin unless a test
        // says otherwise — so the default outcome is a clean four-way draw.
        when(caseRarityRoll.activeBuckets(any(), any(), any())).thenReturn(List.of());
        when(dropSelector.selectWeighted(any())).thenReturn(entry("rl_skin"));
    }

    private static CaseEntry entry(String skinId) {
        CaseEntry entry = new CaseEntry();
        entry.setSkinId(skinId);
        entry.setWeight(1);
        return entry;
    }

    /** "classic_*" so the category unlocks at level 1 and never gates these tests. */
    private static String caseId(int index) {
        return "classic_roundlimit_" + index;
    }

    private UUID newPlayer(String name) {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setDisplayName(name);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        account.setTotalXp(2000L); // well past every category unlock
        account = accountRepository.saveAndFlush(account);
        walletService.createInitialWallet(account.getId(), STARTING_VP);
        return account.getId();
    }

    private List<CaseSelectionRequest> selections(int caseCount, int quantityEach) {
        List<CaseSelectionRequest> out = new ArrayList<>();
        for (int i = 0; i < caseCount; i++) {
            out.add(new CaseSelectionRequest(caseId(i), quantityEach));
        }
        return out;
    }

    @Test
    void fiveCasesTimesFive_isAcceptedAsTwentyFiveOpenings() {
        UUID host = newPlayer("RL_Host25");

        LobbyResponse res = lobbyService.createLobby(host, selections(5, 5), 2);

        assertEquals(25, res.rounds());
        assertEquals(5, res.caseSelections().size());
        // 100 VP x 25 openings, charged once, for real.
        assertEquals(2500L, res.entryCost());
        assertEquals(STARTING_VP - 2500L, walletService.getWalletForAccount(host).vpBalance());
    }

    @Test
    void sixCases_isRefused_andChargesNothing() {
        UUID host = newPlayer("RL_Host6");

        ApiException ex = assertThrows(ApiException.class,
                () -> lobbyService.createLobby(host, selections(6, 5), 2));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(STARTING_VP, walletService.getWalletForAccount(host).vpBalance());
    }

    @Test
    void sixCopiesOfOneCase_isRefused_andChargesNothing() {
        UUID host = newPlayer("RL_Host6x");

        ApiException ex = assertThrows(ApiException.class,
                () -> lobbyService.createLobby(host, selections(1, 6), 2));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(STARTING_VP, walletService.getWalletForAccount(host).vpBalance());
    }

    @Test
    void zeroCopies_isRefused() {
        UUID host = newPlayer("RL_Host0");

        ApiException ex = assertThrows(ApiException.class,
                () -> lobbyService.createLobby(host, selections(1, 0), 2));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    /** Seeds a due, full 25-round lobby (5 cases x 5) with four paid-up real slots. */
    private UUID dueTwentyFiveRoundLobby(List<UUID> players) {
        BattleLobby lobby = new BattleLobby();
        lobby.setCreatorAccountId(players.get(0));
        lobby.setCaseId(caseId(0));
        lobby.setRounds(25);
        lobby.setMaxSlots(4);
        lobby.setEntryCost(2500L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        lobby = lobbyRepository.saveAndFlush(lobby);
        UUID lobbyId = lobby.getId();

        for (int i = 0; i < 5; i++) {
            BattleLobbyCase selection = new BattleLobbyCase();
            selection.setLobbyId(lobbyId);
            selection.setOrdinal(i);
            selection.setCaseId(caseId(i));
            selection.setQuantity(5);
            lobbyCaseRepository.saveAndFlush(selection);
        }
        for (int i = 0; i < players.size(); i++) {
            BattleLobbySlot slot = new BattleLobbySlot();
            slot.setLobbyId(lobbyId);
            slot.setSlotIndex(i);
            slot.setSlotType(SlotType.REAL);
            slot.setAccountId(players.get(i));
            slot.setDisplayName("RL" + i);
            slot.setCreator(i == 0);
            slot.setChargedVp(2500L);
            slot.setLastSeenAt(Instant.now());
            slotRepository.saveAndFlush(slot);
        }
        return lobbyId;
    }

    private UUID assertResolvedWithHundredRolls(UUID lobbyId) {
        BattleLobby resolved = lobbyRepository.findById(lobbyId).orElseThrow();
        assertEquals(LobbyStatus.COMPLETED, resolved.getStatus());
        UUID battleId = resolved.getResultBattleId();

        // 25 rounds x 4 slots = 100 rolls, and every round number is present.
        assertEquals(100, count("SELECT COUNT(*) FROM battle_rolls WHERE battle_id = ?", battleId));
        assertEquals(25, count(
                "SELECT COUNT(DISTINCT round_number) FROM battle_rolls WHERE battle_id = ?", battleId));
        assertEquals(4, count(
                "SELECT COUNT(*) FROM battle_participants WHERE battle_id = ?", battleId));
        return battleId;
    }

    @Test
    void aFullTwentyFiveRoundDraw_resolvesAndGrantsNothing() {
        List<UUID> players = List.of(
                newPlayer("RL_A"), newPlayer("RL_B"), newPlayer("RL_C"), newPlayer("RL_D"));
        UUID lobbyId = dueTwentyFiveRoundLobby(players);

        long startedAt = System.nanoTime();
        lobbyService.resolveDueLobby(lobbyId);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        UUID battleId = assertResolvedWithHundredRolls(lobbyId);
        // Everyone rolled the same skin every round, so all four tie and nobody
        // is granted anything — the draw rule holds at 25 rounds too.
        assertEquals(0, count(
                "SELECT COUNT(*) FROM battle_rolls WHERE battle_id = ? AND granted_inventory_item_id IS NOT NULL",
                battleId));
        for (UUID player : players) {
            assertEquals(0, count("SELECT COUNT(*) FROM inventory_items WHERE account_id = ?", player));
        }
        System.out.println("[BattleRoundLimitIT] 25r x 4 draw resolved in " + elapsedMs + " ms (0 grants)");
    }

    @Test
    void aFullTwentyFiveRoundWin_grantsTheWinnerAllHundredRolls() {
        List<UUID> players = List.of(
                newPlayer("RL_W"), newPlayer("RL_X"), newPlayer("RL_Y"), newPlayer("RL_Z"));
        // Slot 0 rolls the 1000 VP skin for all 25 of its openings; everyone after
        // it rolls the 250 VP one, so there is exactly one winner.
        AtomicInteger call = new AtomicInteger();
        when(dropSelector.selectWeighted(any()))
                .thenAnswer(inv -> entry(call.getAndIncrement() < 25 ? "rl_skin_hi" : "rl_skin"));
        UUID lobbyId = dueTwentyFiveRoundLobby(players);

        long startedAt = System.nanoTime();
        lobbyService.resolveDueLobby(lobbyId);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        UUID battleId = assertResolvedWithHundredRolls(lobbyId);
        assertEquals(0, lobbyRepository.findById(lobbyId).orElseThrow().getWinnerSlotIndex());
        // Winner-takes-all at full length: 100 inventory rows in one transaction.
        assertEquals(100, count(
                "SELECT COUNT(*) FROM battle_rolls WHERE battle_id = ? AND granted_inventory_item_id IS NOT NULL",
                battleId));
        assertEquals(100, count(
                "SELECT COUNT(*) FROM inventory_items WHERE account_id = ?", players.get(0)));
        for (int i = 1; i < players.size(); i++) {
            assertEquals(0, count(
                    "SELECT COUNT(*) FROM inventory_items WHERE account_id = ?", players.get(i)));
        }
        System.out.println("[BattleRoundLimitIT] 25r x 4 win resolved in " + elapsedMs
                + " ms (100 inventory inserts under the lobby lock)");
    }

    private int count(String sql, Object arg) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arg);
        return value == null ? 0 : value;
    }
}
