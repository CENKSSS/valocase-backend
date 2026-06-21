package com.cenk.valocase.leaderboard.dto;

/**
 * One ranked row of a leaderboard. {@code value} is the primary metric (battles,
 * win-rate percent, or VP) kept as a plain number for the client to format, and
 * {@code secondaryValue} is an optional pre-formatted hint (e.g. "49%").
 */
public record LeaderboardEntryResponse(
        int rank,
        String displayName,
        String avatarKey,
        long value,
        String secondaryValue
) {
}
