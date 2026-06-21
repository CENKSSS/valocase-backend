package com.cenk.valocase.leaderboard.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.leaderboard.domain.LeaderboardType;
import com.cenk.valocase.leaderboard.dto.LeaderboardResponse;
import com.cenk.valocase.leaderboard.repository.BattleStatRow;
import com.cenk.valocase.leaderboard.repository.BattleTotals;
import com.cenk.valocase.leaderboard.repository.LeaderboardRepository;
import com.cenk.valocase.leaderboard.repository.WalletStatRow;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.repository.WalletRepository;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LeaderboardServiceTest {

    @Mock private LeaderboardRepository leaderboardRepository;
    @Mock private WalletRepository walletRepository;

    @InjectMocks private LeaderboardService service;

    private static final UUID VIEWER = UUID.randomUUID();

    private static Account viewer() {
        Account a = new Account();
        a.setId(VIEWER);
        a.setDisplayName("CENK");
        a.setAvatarId("avatar_3");
        return a;
    }

    private static BattleStatRow battleRow(String name, long battles, long wins) {
        return new BattleStatRow() {
            public UUID getAccountId() { return UUID.randomUUID(); }
            public String getDisplayName() { return name; }
            public String getAvatarId() { return "avatar_1"; }
            public long getBattles() { return battles; }
            public long getWins() { return wins; }
        };
    }

    private static BattleTotals totals(long battles, long wins) {
        return new BattleTotals() {
            public long getBattles() { return battles; }
            public long getWins() { return wins; }
        };
    }

    private static WalletStatRow walletRow(String name, long value) {
        return new WalletStatRow() {
            public UUID getAccountId() { return UUID.randomUUID(); }
            public String getDisplayName() { return name; }
            public String getAvatarId() { return "avatar_1"; }
            public long getValue() { return value; }
        };
    }

    @Test
    void mostBattlesRanksEntriesAndPlacesViewerOutsideTopTen() {
        when(leaderboardRepository.topByMostBattles(eq(10)))
                .thenReturn(List.of(battleRow("Top", 35, 17), battleRow("Second", 20, 4)));
        when(leaderboardRepository.battleTotalsForAccount(VIEWER)).thenReturn(totals(8, 3));
        when(leaderboardRepository.countAccountsWithMoreBattles(8)).thenReturn(11L);

        LeaderboardResponse response = service.leaderboard(LeaderboardType.MOST_BATTLES, viewer());

        assertEquals("MOST_BATTLES", response.type());
        assertEquals(2, response.entries().size());
        assertEquals(1, response.entries().get(0).rank());
        assertEquals(35, response.entries().get(0).value());
        assertEquals("49%", response.entries().get(0).secondaryValue());
        assertEquals(12, response.me().rank());
        assertEquals(">10", response.me().rankLabel());
        assertEquals(8, response.me().value());
        assertEquals("38%", response.me().secondaryValue());
        assertEquals("CENK", response.me().displayName());
    }

    @Test
    void mostBattlesMarksViewerWithNoBattlesUnranked() {
        when(leaderboardRepository.topByMostBattles(eq(10))).thenReturn(List.of());
        when(leaderboardRepository.battleTotalsForAccount(VIEWER)).thenReturn(totals(0, 0));

        LeaderboardResponse response = service.leaderboard(LeaderboardType.MOST_BATTLES, viewer());

        assertNull(response.me().rank());
        assertEquals("Unranked", response.me().rankLabel());
        assertEquals(0, response.me().value());
    }

    @Test
    void winRateExposesPercentValueAndGatesBelowThreshold() {
        when(leaderboardRepository.topByWinRate(eq(10L), eq(10)))
                .thenReturn(List.of(battleRow("Sharp", 40, 30)));
        when(leaderboardRepository.battleTotalsForAccount(VIEWER)).thenReturn(totals(5, 5));

        LeaderboardResponse response = service.leaderboard(LeaderboardType.BEST_BATTLE_WIN_RATE, viewer());

        assertEquals("BEST_BATTLE_WIN_RATE", response.type());
        assertEquals(75, response.entries().get(0).value());
        assertEquals("40 battles", response.entries().get(0).secondaryValue());
        assertNull(response.me().rank());
        assertEquals("Unranked", response.me().rankLabel());
        assertEquals(100, response.me().value());
        assertEquals("5 battles", response.me().secondaryValue());
    }

    @Test
    void winRateRanksQualifiedViewer() {
        when(leaderboardRepository.topByWinRate(anyLong(), eq(10))).thenReturn(List.of());
        when(leaderboardRepository.battleTotalsForAccount(VIEWER)).thenReturn(totals(20, 13));
        when(leaderboardRepository.countAccountsAboveWinRate(10L, 13L, 20L)).thenReturn(2L);

        LeaderboardResponse response = service.leaderboard(LeaderboardType.BEST_BATTLE_WIN_RATE, viewer());

        assertEquals(3, response.me().rank());
        assertEquals("3", response.me().rankLabel());
        assertEquals(65, response.me().value());
    }

    @Test
    void highestWalletRanksByBalance() {
        when(leaderboardRepository.topByWalletValue(eq(10)))
                .thenReturn(List.of(walletRow("Rich", 50_000), walletRow("Saver", 12_000)));
        Wallet wallet = new Wallet();
        wallet.setVpBalance(9_000);
        when(walletRepository.findByAccountId(VIEWER)).thenReturn(Optional.of(wallet));
        when(leaderboardRepository.countWalletsAbove(9_000L)).thenReturn(4L);

        LeaderboardResponse response = service.leaderboard(LeaderboardType.HIGHEST_WALLET_VALUE, viewer());

        assertEquals("HIGHEST_WALLET_VALUE", response.type());
        assertEquals(50_000, response.entries().get(0).value());
        assertNull(response.entries().get(0).secondaryValue());
        assertEquals(5, response.me().rank());
        assertEquals("5", response.me().rankLabel());
        assertEquals(9_000, response.me().value());
    }
}
