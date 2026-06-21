package com.cenk.valocase.leaderboard.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.account.domain.Account;

/**
 * Read-only ranking queries for the Battle-screen leaderboards.
 *
 * <p>Completed real-player battle statistics combine two sources: resolved public
 * lobbies (battle_lobby_slots joined to COMPLETED battle_lobbies, REAL slots only,
 * a win when the slot index matches the lobby winner) and legacy solo-vs-bots
 * battles (the battles table, attributed to its creator account, a win when
 * user_won). A lobby resolution also writes a battles row referenced by
 * battle_lobbies.result_battle_id, so the bot-battle branch excludes those rows
 * to avoid double counting. Only ACTIVE real accounts appear; bots have no row.
 * All ordering uses deterministic tie breakers (account_id last) so paging and
 * rank counts agree.
 */
public interface LeaderboardRepository extends Repository<Account, UUID> {

    @Query(value = """
            SELECT a.id AS accountId, a.display_name AS displayName, a.avatar_id AS avatarId,
                   agg.battles AS battles, agg.wins AS wins
            FROM (
                SELECT src.account_id AS account_id,
                       SUM(src.battles) AS battles,
                       SUM(src.wins) AS wins
                FROM (
                    SELECT s.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN s.slot_index = l.winner_slot_index THEN 1 ELSE 0 END) AS wins
                    FROM battle_lobby_slots s
                    JOIN battle_lobbies l ON l.id = s.lobby_id
                    WHERE l.status = 'COMPLETED' AND s.slot_type = 'REAL' AND s.account_id IS NOT NULL
                    GROUP BY s.account_id
                    UNION ALL
                    SELECT b.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN b.user_won THEN 1 ELSE 0 END) AS wins
                    FROM battles b
                    WHERE NOT EXISTS (
                        SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
                    GROUP BY b.account_id
                ) src
                GROUP BY src.account_id
            ) agg
            JOIN accounts a ON a.id = agg.account_id AND a.status = 'ACTIVE'
            ORDER BY agg.battles DESC, a.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<BattleStatRow> topByMostBattles(@Param("limit") int limit);

    @Query(value = """
            SELECT a.id AS accountId, a.display_name AS displayName, a.avatar_id AS avatarId,
                   agg.battles AS battles, agg.wins AS wins
            FROM (
                SELECT src.account_id AS account_id,
                       SUM(src.battles) AS battles,
                       SUM(src.wins) AS wins
                FROM (
                    SELECT s.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN s.slot_index = l.winner_slot_index THEN 1 ELSE 0 END) AS wins
                    FROM battle_lobby_slots s
                    JOIN battle_lobbies l ON l.id = s.lobby_id
                    WHERE l.status = 'COMPLETED' AND s.slot_type = 'REAL' AND s.account_id IS NOT NULL
                    GROUP BY s.account_id
                    UNION ALL
                    SELECT b.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN b.user_won THEN 1 ELSE 0 END) AS wins
                    FROM battles b
                    WHERE NOT EXISTS (
                        SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
                    GROUP BY b.account_id
                ) src
                GROUP BY src.account_id
            ) agg
            JOIN accounts a ON a.id = agg.account_id AND a.status = 'ACTIVE'
            WHERE agg.battles >= :minBattles
            ORDER BY (1.0 * agg.wins / agg.battles) DESC, agg.battles DESC, a.id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<BattleStatRow> topByWinRate(@Param("minBattles") long minBattles, @Param("limit") int limit);

    @Query(value = """
            SELECT w.account_id AS accountId, a.display_name AS displayName, a.avatar_id AS avatarId,
                   w.vp_balance AS value
            FROM wallets w
            JOIN accounts a ON a.id = w.account_id
            WHERE a.status = 'ACTIVE'
            ORDER BY w.vp_balance DESC, w.account_id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<WalletStatRow> topByWalletValue(@Param("limit") int limit);

    @Query(value = """
            SELECT COALESCE(SUM(src.battles), 0) AS battles,
                   COALESCE(SUM(src.wins), 0) AS wins
            FROM (
                SELECT COUNT(*) AS battles,
                       SUM(CASE WHEN s.slot_index = l.winner_slot_index THEN 1 ELSE 0 END) AS wins
                FROM battle_lobby_slots s
                JOIN battle_lobbies l ON l.id = s.lobby_id
                WHERE l.status = 'COMPLETED' AND s.slot_type = 'REAL' AND s.account_id = :accountId
                UNION ALL
                SELECT COUNT(*) AS battles,
                       SUM(CASE WHEN b.user_won THEN 1 ELSE 0 END) AS wins
                FROM battles b
                WHERE b.account_id = :accountId
                  AND NOT EXISTS (
                      SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
            ) src
            """, nativeQuery = true)
    BattleTotals battleTotalsForAccount(@Param("accountId") UUID accountId);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT src.account_id AS account_id, SUM(src.battles) AS battles
                FROM (
                    SELECT s.account_id AS account_id, COUNT(*) AS battles
                    FROM battle_lobby_slots s
                    JOIN battle_lobbies l ON l.id = s.lobby_id
                    WHERE l.status = 'COMPLETED' AND s.slot_type = 'REAL' AND s.account_id IS NOT NULL
                    GROUP BY s.account_id
                    UNION ALL
                    SELECT b.account_id AS account_id, COUNT(*) AS battles
                    FROM battles b
                    WHERE NOT EXISTS (
                        SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
                    GROUP BY b.account_id
                ) src
                JOIN accounts a ON a.id = src.account_id AND a.status = 'ACTIVE'
                GROUP BY src.account_id
                HAVING SUM(src.battles) > :myBattles
            ) ranked
            """, nativeQuery = true)
    long countAccountsWithMoreBattles(@Param("myBattles") long myBattles);

    @Query(value = """
            SELECT COUNT(*) FROM (
                SELECT src.account_id AS account_id,
                       SUM(src.battles) AS battles,
                       SUM(src.wins) AS wins
                FROM (
                    SELECT s.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN s.slot_index = l.winner_slot_index THEN 1 ELSE 0 END) AS wins
                    FROM battle_lobby_slots s
                    JOIN battle_lobbies l ON l.id = s.lobby_id
                    WHERE l.status = 'COMPLETED' AND s.slot_type = 'REAL' AND s.account_id IS NOT NULL
                    GROUP BY s.account_id
                    UNION ALL
                    SELECT b.account_id AS account_id,
                           COUNT(*) AS battles,
                           SUM(CASE WHEN b.user_won THEN 1 ELSE 0 END) AS wins
                    FROM battles b
                    WHERE NOT EXISTS (
                        SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
                    GROUP BY b.account_id
                ) src
                JOIN accounts a ON a.id = src.account_id AND a.status = 'ACTIVE'
                GROUP BY src.account_id
                HAVING SUM(src.battles) >= :minBattles
                   AND (
                        SUM(src.wins) * :myBattles > :myWins * SUM(src.battles)
                     OR (
                        SUM(src.wins) * :myBattles = :myWins * SUM(src.battles)
                        AND SUM(src.battles) > :myBattles)
                   )
            ) ranked
            """, nativeQuery = true)
    long countAccountsAboveWinRate(
            @Param("minBattles") long minBattles,
            @Param("myWins") long myWins,
            @Param("myBattles") long myBattles);

    @Query(value = """
            SELECT COUNT(*)
            FROM wallets w
            JOIN accounts a ON a.id = w.account_id
            WHERE a.status = 'ACTIVE' AND w.vp_balance > :myBalance
            """, nativeQuery = true)
    long countWalletsAbove(@Param("myBalance") long myBalance);
}
