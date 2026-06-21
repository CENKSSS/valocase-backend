package com.cenk.valocase.leaderboard.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.leaderboard.domain.LeaderboardType;
import com.cenk.valocase.leaderboard.dto.LeaderboardEntryResponse;
import com.cenk.valocase.leaderboard.dto.LeaderboardMeResponse;
import com.cenk.valocase.leaderboard.dto.LeaderboardResponse;
import com.cenk.valocase.leaderboard.repository.BattleStatRow;
import com.cenk.valocase.leaderboard.repository.BattleTotals;
import com.cenk.valocase.leaderboard.repository.LeaderboardRepository;
import com.cenk.valocase.leaderboard.repository.WalletStatRow;
import com.cenk.valocase.wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;

/**
 * Builds the three Battle-screen leaderboards. Ranking is done entirely in SQL
 * (top-N plus a count of accounts strictly ahead of the viewer) so no full table
 * scan happens in memory. The viewer's own standing is always computed so the
 * client can render its summary panel even when the viewer is inside the top 10.
 */
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    static final int TOP_LIMIT = 10;

    /** Completed battles a player needs before they appear on the win-rate board. */
    static final int MIN_WIN_RATE_BATTLES = 10;

    private static final String UNRANKED_LABEL = "Unranked";
    private static final String OUT_OF_TOP_LABEL = ">" + TOP_LIMIT;

    private final LeaderboardRepository leaderboardRepository;
    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    public LeaderboardResponse leaderboard(LeaderboardType type, Account viewer) {
        return switch (type) {
            case MOST_BATTLES -> mostBattles(viewer);
            case BEST_BATTLE_WIN_RATE -> bestWinRate(viewer);
            case HIGHEST_WALLET_VALUE -> highestWallet(viewer);
        };
    }

    private LeaderboardResponse mostBattles(Account viewer) {
        List<BattleStatRow> rows = leaderboardRepository.topByMostBattles(TOP_LIMIT);
        List<LeaderboardEntryResponse> entries = new ArrayList<>(rows.size());
        int rank = 1;
        for (BattleStatRow row : rows) {
            entries.add(new LeaderboardEntryResponse(
                    rank++,
                    AccountService.resolveDisplayName(row.getDisplayName(), row.getAccountId()),
                    AccountService.resolveAvatarId(row.getAvatarId()),
                    row.getBattles(),
                    winRateLabel(row.getWins(), row.getBattles())));
        }

        BattleTotals me = leaderboardRepository.battleTotalsForAccount(viewer.getId());
        long battles = me.getBattles();
        Integer rankValue = battles == 0
                ? null
                : (int) (leaderboardRepository.countAccountsWithMoreBattles(battles) + 1);
        LeaderboardMeResponse meResponse = new LeaderboardMeResponse(
                rankValue,
                rankLabel(rankValue),
                AccountService.resolveDisplayName(viewer.getDisplayName(), viewer.getId()),
                AccountService.resolveAvatarId(viewer.getAvatarId()),
                battles,
                winRateLabel(me.getWins(), battles));

        return new LeaderboardResponse(LeaderboardType.MOST_BATTLES.name(), entries, meResponse);
    }

    private LeaderboardResponse bestWinRate(Account viewer) {
        List<BattleStatRow> rows = leaderboardRepository.topByWinRate(MIN_WIN_RATE_BATTLES, TOP_LIMIT);
        List<LeaderboardEntryResponse> entries = new ArrayList<>(rows.size());
        int rank = 1;
        for (BattleStatRow row : rows) {
            entries.add(new LeaderboardEntryResponse(
                    rank++,
                    AccountService.resolveDisplayName(row.getDisplayName(), row.getAccountId()),
                    AccountService.resolveAvatarId(row.getAvatarId()),
                    winRatePercent(row.getWins(), row.getBattles()),
                    battleCountLabel(row.getBattles())));
        }

        BattleTotals me = leaderboardRepository.battleTotalsForAccount(viewer.getId());
        long battles = me.getBattles();
        Integer rankValue = battles < MIN_WIN_RATE_BATTLES
                ? null
                : (int) (leaderboardRepository.countAccountsAboveWinRate(
                        MIN_WIN_RATE_BATTLES, me.getWins(), battles) + 1);
        LeaderboardMeResponse meResponse = new LeaderboardMeResponse(
                rankValue,
                rankLabel(rankValue),
                AccountService.resolveDisplayName(viewer.getDisplayName(), viewer.getId()),
                AccountService.resolveAvatarId(viewer.getAvatarId()),
                winRatePercent(me.getWins(), battles),
                battleCountLabel(battles));

        return new LeaderboardResponse(LeaderboardType.BEST_BATTLE_WIN_RATE.name(), entries, meResponse);
    }

    private LeaderboardResponse highestWallet(Account viewer) {
        List<WalletStatRow> rows = leaderboardRepository.topByWalletValue(TOP_LIMIT);
        List<LeaderboardEntryResponse> entries = new ArrayList<>(rows.size());
        int rank = 1;
        for (WalletStatRow row : rows) {
            entries.add(new LeaderboardEntryResponse(
                    rank++,
                    AccountService.resolveDisplayName(row.getDisplayName(), row.getAccountId()),
                    AccountService.resolveAvatarId(row.getAvatarId()),
                    row.getValue(),
                    null));
        }

        long balance = walletRepository.findByAccountId(viewer.getId())
                .map(w -> w.getVpBalance())
                .orElse(0L);
        int rankValue = (int) (leaderboardRepository.countWalletsAbove(balance) + 1);
        LeaderboardMeResponse meResponse = new LeaderboardMeResponse(
                rankValue,
                rankLabel(rankValue),
                AccountService.resolveDisplayName(viewer.getDisplayName(), viewer.getId()),
                AccountService.resolveAvatarId(viewer.getAvatarId()),
                balance,
                null);

        return new LeaderboardResponse(LeaderboardType.HIGHEST_WALLET_VALUE.name(), entries, meResponse);
    }

    private static long winRatePercent(long wins, long battles) {
        if (battles <= 0) {
            return 0;
        }
        return Math.round(100.0 * wins / battles);
    }

    private static String winRateLabel(long wins, long battles) {
        return winRatePercent(wins, battles) + "%";
    }

    private static String battleCountLabel(long battles) {
        return battles + " battles";
    }

    private static String rankLabel(Integer rank) {
        if (rank == null) {
            return UNRANKED_LABEL;
        }
        return rank > TOP_LIMIT ? OUT_OF_TOP_LABEL : String.valueOf(rank);
    }
}
