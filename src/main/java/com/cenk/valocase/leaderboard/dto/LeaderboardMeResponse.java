package com.cenk.valocase.leaderboard.dto;

/**
 * The requesting player's own standing, always returned so the client can show a
 * consistent summary panel even when the player is inside (or absent from) the
 * top 10. {@code rank} is null when unranked; {@code rankLabel} carries the
 * display string (">10", "Unranked", or the numeric rank).
 */
public record LeaderboardMeResponse(
        Integer rank,
        String rankLabel,
        String displayName,
        String avatarKey,
        long value,
        String secondaryValue
) {
}
