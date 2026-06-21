package com.cenk.valocase.leaderboard.repository;

import java.util.UUID;

/** Per-account battle aggregate used by the Most Battles and Win Rate boards. */
public interface BattleStatRow {

    UUID getAccountId();

    String getDisplayName();

    String getAvatarId();

    long getBattles();

    long getWins();
}
