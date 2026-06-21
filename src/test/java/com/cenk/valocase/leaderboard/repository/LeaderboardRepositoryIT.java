package com.cenk.valocase.leaderboard.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.repository.AccountRepository;

/**
 * Verifies the combined leaderboard battle statistics (public lobbies + legacy
 * bot battles) against a real Postgres, including de-duplication of the battles
 * row a lobby writes on resolution.
 */
@SpringBootTest
@Testcontainers
class LeaderboardRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaderboardRepository leaderboardRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbc;

    private String caseId;
    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void seed() {
        caseId = jdbc.queryForObject("SELECT id FROM case_definitions LIMIT 1", String.class);
        accountA = createAccount("PlayerA");
        accountB = createAccount("PlayerB");
        createAccount("PlayerC");

        // A: 3 standalone bot battles, 2 won.
        insertBotBattle(accountA, true);
        insertBotBattle(accountA, true);
        insertBotBattle(accountA, false);
        // B: 1 standalone bot battle, lost.
        insertBotBattle(accountB, false);

        // One completed lobby: A (slot 0) beats B (slot 1) and a bot (slot 2).
        // Its resolution writes a battles row that must NOT be counted again.
        UUID lobbyId = UUID.randomUUID();
        UUID resultBattleId = insertBotBattleReturningId(accountA, true);
        insertCompletedLobby(lobbyId, accountA, 0, resultBattleId);
        insertSlot(lobbyId, 0, "REAL", accountA, true);
        insertSlot(lobbyId, 1, "REAL", accountB, false);
        insertSlot(lobbyId, 2, "BOT", null, false);
    }

    @Test
    void combinesBotAndLobbyBattlesWithoutDoubleCounting() {
        BattleTotals a = leaderboardRepository.battleTotalsForAccount(accountA);
        assertEquals(4, a.getBattles());
        assertEquals(3, a.getWins());

        BattleTotals b = leaderboardRepository.battleTotalsForAccount(accountB);
        assertEquals(2, b.getBattles());
        assertEquals(0, b.getWins());
    }

    @Test
    void topMostBattlesRanksRealAccountsOnly() {
        List<BattleStatRow> top = leaderboardRepository.topByMostBattles(10);
        assertEquals(2, top.size());
        assertEquals(accountA, top.get(0).getAccountId());
        assertEquals(4, top.get(0).getBattles());
        assertEquals(accountB, top.get(1).getAccountId());
        assertEquals(2, top.get(1).getBattles());
    }

    @Test
    void winRateUsesCombinedStats() {
        List<BattleStatRow> top = leaderboardRepository.topByWinRate(1, 10);
        assertEquals(accountA, top.get(0).getAccountId());
        assertEquals(3, top.get(0).getWins());
        assertEquals(4, top.get(0).getBattles());

        assertEquals(1, leaderboardRepository.countAccountsWithMoreBattles(2));
        assertEquals(0, leaderboardRepository.countAccountsAboveWinRate(1, 3, 4));
        assertEquals(1, leaderboardRepository.countAccountsAboveWinRate(1, 0, 2));
    }

    private UUID createAccount(String name) {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        account.setDisplayName(name);
        account.setAvatarId("avatar_1");
        return accountRepository.save(account).getId();
    }

    private void insertBotBattle(UUID accountId, boolean userWon) {
        insertBotBattleReturningId(accountId, userWon);
    }

    private UUID insertBotBattleReturningId(UUID accountId, boolean userWon) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO battles
                    (id, account_id, case_id, rounds, participant_count, entry_cost,
                     winner_index, user_won, created_at)
                VALUES (?, ?, ?, 1, 2, 0, ?, ?, ?)
                """, id, accountId, caseId, userWon ? 0 : 1, userWon, Timestamp.from(Instant.now()));
        return id;
    }

    private void insertCompletedLobby(UUID lobbyId, UUID creatorId, int winnerSlot, UUID resultBattleId) {
        jdbc.update("""
                INSERT INTO battle_lobbies
                    (id, creator_account_id, case_id, rounds, max_slots, entry_cost, status,
                     created_at, completed_at, winner_slot_index, result_battle_id, version)
                VALUES (?, ?, ?, 1, 3, 0, 'COMPLETED', ?, ?, ?, ?, 0)
                """, lobbyId, creatorId, caseId, Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()), winnerSlot, resultBattleId);
    }

    private void insertSlot(UUID lobbyId, int slotIndex, String slotType, UUID accountId, boolean creator) {
        jdbc.update("""
                INSERT INTO battle_lobby_slots
                    (id, lobby_id, slot_index, slot_type, account_id, display_name, is_creator, charged_vp)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """, UUID.randomUUID(), lobbyId, slotIndex, slotType, accountId,
                accountId == null ? "Bot" : "Player", creator);
    }
}
