package com.cenk.valocase.leaderboard.dto;

import java.util.List;

/**
 * A leaderboard tab: its type, the top ranked entries, and the requesting
 * player's own standing.
 */
public record LeaderboardResponse(
        String type,
        List<LeaderboardEntryResponse> entries,
        LeaderboardMeResponse me
) {
}
