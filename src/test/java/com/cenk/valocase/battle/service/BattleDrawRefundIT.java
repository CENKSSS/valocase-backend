package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.BattleLobbySlot;
import com.cenk.valocase.battle.domain.LobbyStatus;
import com.cenk.valocase.battle.domain.SlotType;
import com.cenk.valocase.battle.repository.BattleLobbyRepository;
import com.cenk.valocase.battle.repository.BattleLobbySlotRepository;
import com.cenk.valocase.caseopening.service.CaseRarityRoll;
import com.cenk.valocase.caseopening.service.DropSelector;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.wallet.service.WalletService;

/**
 * Partial-draw refunds against a real Flyway-migrated Postgres: real wallet
 * rows, real inventory rows, real transaction boundary. Only the dice are fixed
 * — {@link DropSelector} is replaced so each slot lands on a known total —
 * because the rule under test is who gets their entry back, not the roll.
 *
 * <p>This project has no Docker, so unlike the other {@code *IT} classes here
 * there is no Testcontainers database. Point the usual Spring datasource
 * properties at any Postgres and run it:
 *
 * <pre>
 * SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/somedb \
 * SPRING_DATASOURCE_USERNAME=postgres SPRING_DATASOURCE_PASSWORD=... \
 *   mvnw test -Dtest=BattleDrawRefundIT
 * </pre>
 */
@SpringBootTest
class BattleDrawRefundIT {

    private static final long ENTRY_COST = 500L;
    private static final long STARTING_VP = 10_000L;
    private static final String CASE_ID = "draw_it_case";

    @MockitoBean DropSelector dropSelector;
    @MockitoBean CaseRarityRoll caseRarityRoll;

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired WalletService walletService;
    @Autowired BattleLobbyRepository lobbyRepository;
    @Autowired BattleLobbySlotRepository slotRepository;
    @Autowired BattleLobbyService lobbyService;

    @BeforeEach
    void seedCatalog() {
        // A private case whose four skins have distinct values, so a fixed roll
        // order produces exactly the totals each test wants.
        jdbc.update("""
                INSERT INTO case_definitions (id, display_name, price_vp, active)
                VALUES (?, 'Draw IT Case', 500, TRUE) ON CONFLICT (id) DO NOTHING
                """, CASE_ID);
        for (int vp : new int[]{900, 500, 300}) {
            jdbc.update("""
                    INSERT INTO skins (id, display_name, weapon, rarity, vp_value, active)
                    VALUES (?, ?, 'Vandal', 'Select', ?, TRUE) ON CONFLICT (id) DO NOTHING
                    """, skinId(vp), "Draw IT " + vp, vp);
            jdbc.update("""
                    INSERT INTO case_entries (id, case_id, skin_id, weight)
                    VALUES (gen_random_uuid(), ?, ?, 1)
                    ON CONFLICT ON CONSTRAINT uq_case_entries_case_skin DO NOTHING
                    """, CASE_ID, skinId(vp));
        }
        // Force the flat pool path so the fixed DropSelector decides every roll.
        when(caseRarityRoll.activeBuckets(any(), any(), any())).thenReturn(List.of());
    }

    private static String skinId(int vp) {
        return "draw_it_skin_" + vp;
    }

    private CaseEntry entryWorth(int vp) {
        CaseEntry entry = new CaseEntry();
        entry.setCaseId(CASE_ID);
        entry.setSkinId(skinId(vp));
        entry.setWeight(1);
        return entry;
    }

    /** Fixes the roll order: slot i ends on {@code vpPerSlot[i]} (one round each). */
    private void fixRolls(int... vpPerSlot) {
        CaseEntry first = entryWorth(vpPerSlot[0]);
        CaseEntry[] rest = new CaseEntry[vpPerSlot.length - 1];
        for (int i = 1; i < vpPerSlot.length; i++) {
            rest[i - 1] = entryWorth(vpPerSlot[i]);
        }
        when(dropSelector.selectWeighted(any())).thenReturn(first, rest);
    }

    private UUID newPlayer(String name) {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setDisplayName(name);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        account = accountRepository.saveAndFlush(account);
        walletService.createInitialWallet(account.getId(), STARTING_VP);
        return account.getId();
    }

    /** A full, due lobby whose real players have each already paid the entry. */
    private UUID dueLobbyWith(List<UUID> players) {
        BattleLobby lobby = new BattleLobby();
        lobby.setCreatorAccountId(players.get(0));
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(1);
        lobby.setMaxSlots(players.size());
        lobby.setEntryCost(ENTRY_COST);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        lobby = lobbyRepository.saveAndFlush(lobby);

        for (int i = 0; i < players.size(); i++) {
            BattleLobbySlot slot = new BattleLobbySlot();
            slot.setLobbyId(lobby.getId());
            slot.setSlotIndex(i);
            slot.setSlotType(SlotType.REAL);
            slot.setAccountId(players.get(i));
            slot.setDisplayName("P" + i);
            slot.setCreator(i == 0);
            slot.setChargedVp(ENTRY_COST);
            slot.setLastSeenAt(Instant.now());
            slotRepository.saveAndFlush(slot);
            // Everyone really paid to be here.
            walletService.debit(players.get(i), ENTRY_COST,
                    BattleLobbyService.REASON_LOBBY_ENTRY, lobby.getId());
        }
        return lobby.getId();
    }

    private long balanceOf(UUID accountId) {
        return walletService.getWalletForAccount(accountId).vpBalance();
    }

    private int inventoryCount(UUID accountId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM inventory_items WHERE account_id = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    @Test
    void twoSharingTheTop_areRefunded_andTheTwoBelowAreNot() {
        List<UUID> players = List.of(
                newPlayer("TopA"), newPlayer("TopB"), newPlayer("Mid"), newPlayer("Low"));
        fixRolls(900, 900, 500, 300);
        UUID lobbyId = dueLobbyWith(players);

        // Everyone is down the entry cost at this point.
        for (UUID player : players) {
            assertEquals(STARTING_VP - ENTRY_COST, balanceOf(player));
        }

        lobbyService.resolveDueLobby(lobbyId);

        // The two tied at 900 got their entry back; the two below kept nothing.
        assertEquals(STARTING_VP, balanceOf(players.get(0)));
        assertEquals(STARTING_VP, balanceOf(players.get(1)));
        assertEquals(STARTING_VP - ENTRY_COST, balanceOf(players.get(2)));
        assertEquals(STARTING_VP - ENTRY_COST, balanceOf(players.get(3)));

        // A draw hands the loot to nobody, not even the tied players.
        for (UUID player : players) {
            assertEquals(0, inventoryCount(player));
        }
        assertEquals(LobbyStatus.COMPLETED, lobbyRepository.findById(lobbyId).orElseThrow().getStatus());
    }

    @Test
    void threeSharingTheTop_areRefunded() {
        List<UUID> players = List.of(
                newPlayer("T1"), newPlayer("T2"), newPlayer("T3"), newPlayer("Bottom"));
        fixRolls(900, 900, 900, 300);
        UUID lobbyId = dueLobbyWith(players);

        lobbyService.resolveDueLobby(lobbyId);

        assertEquals(STARTING_VP, balanceOf(players.get(0)));
        assertEquals(STARTING_VP, balanceOf(players.get(1)));
        assertEquals(STARTING_VP, balanceOf(players.get(2)));
        assertEquals(STARTING_VP - ENTRY_COST, balanceOf(players.get(3)));
    }

    @Test
    void tieBelowTheTop_isANormalWin_soNobodyIsRefunded() {
        List<UUID> players = List.of(
                newPlayer("Winner"), newPlayer("TieA"), newPlayer("TieB"), newPlayer("Last"));
        fixRolls(900, 500, 500, 300);
        UUID lobbyId = dueLobbyWith(players);

        lobbyService.resolveDueLobby(lobbyId);

        // Nobody gets money back; the single top scorer takes all four rolls.
        for (UUID player : players) {
            assertEquals(STARTING_VP - ENTRY_COST, balanceOf(player));
        }
        assertEquals(4, inventoryCount(players.get(0)));
        assertEquals(0, inventoryCount(players.get(1)));
    }

    @Test
    void resolvingTwice_doesNotRefundTwice() {
        List<UUID> players = List.of(
                newPlayer("DupA"), newPlayer("DupB"), newPlayer("DupC"), newPlayer("DupD"));
        fixRolls(900, 900, 500, 300);
        UUID lobbyId = dueLobbyWith(players);

        lobbyService.resolveDueLobby(lobbyId);
        lobbyService.resolveDueLobby(lobbyId);

        // Still exactly one refund each, not two.
        assertEquals(STARTING_VP, balanceOf(players.get(0)));
        assertEquals(STARTING_VP, balanceOf(players.get(1)));
        assertTrue(balanceOf(players.get(0)) < STARTING_VP + ENTRY_COST);
    }

    @Test
    void freeLobbyDraw_leavesEveryWalletUntouched() {
        List<UUID> players = List.of(newPlayer("FreeA"), newPlayer("FreeB"));
        fixRolls(900, 900);

        BattleLobby lobby = new BattleLobby();
        lobby.setCreatorAccountId(players.get(0));
        lobby.setCaseId(CASE_ID);
        lobby.setRounds(1);
        lobby.setMaxSlots(2);
        lobby.setEntryCost(0L);
        lobby.setStatus(LobbyStatus.STARTING);
        lobby.setCreatedAt(Instant.now().minus(30, ChronoUnit.SECONDS));
        lobby.setReadyAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        lobby.setEvent(true);
        lobby = lobbyRepository.saveAndFlush(lobby);
        for (int i = 0; i < players.size(); i++) {
            BattleLobbySlot slot = new BattleLobbySlot();
            slot.setLobbyId(lobby.getId());
            slot.setSlotIndex(i);
            slot.setSlotType(SlotType.REAL);
            slot.setAccountId(players.get(i));
            slot.setDisplayName("F" + i);
            slot.setCreator(false);
            slot.setChargedVp(0L);
            slot.setLastSeenAt(Instant.now());
            slotRepository.saveAndFlush(slot);
        }

        lobbyService.resolveDueLobby(lobby.getId());

        for (UUID player : players) {
            assertEquals(STARTING_VP, balanceOf(player));
            assertEquals(0, inventoryCount(player));
        }
    }
}
