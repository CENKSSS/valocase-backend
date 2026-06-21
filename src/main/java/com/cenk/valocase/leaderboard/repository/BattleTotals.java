package com.cenk.valocase.leaderboard.repository;

/** The requesting player's own completed-battle aggregate. */
public interface BattleTotals {

    long getBattles();

    long getWins();
}
