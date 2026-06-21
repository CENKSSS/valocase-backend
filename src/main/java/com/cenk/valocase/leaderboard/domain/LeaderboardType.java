package com.cenk.valocase.leaderboard.domain;

/**
 * The three Battle-screen leaderboards.
 *
 * <ul>
 *   <li>{@code MOST_BATTLES} – ranked by completed real-player battles played.</li>
 *   <li>{@code BEST_BATTLE_WIN_RATE} – ranked by win rate, gated by a minimum
 *       completed battle count so a single lucky win cannot top the board.</li>
 *   <li>{@code HIGHEST_WALLET_VALUE} – ranked by current wallet VP balance.</li>
 * </ul>
 */
public enum LeaderboardType {
    MOST_BATTLES,
    BEST_BATTLE_WIN_RATE,
    HIGHEST_WALLET_VALUE
}
