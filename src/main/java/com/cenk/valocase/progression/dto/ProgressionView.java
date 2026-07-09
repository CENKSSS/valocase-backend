package com.cenk.valocase.progression.dto;

import java.util.List;

/**
 * Player progression snapshot, exposed on the startup/bootstrap and wallet
 * responses so the client can render level, XP bar and unlocked categories.
 *
 * @param currentLevelXp            XP progress within the current level
 * @param xpRequiredForNextLevel    XP span of the current level (0 at max level)
 * @param currentLevelXpThreshold   total XP at which the current level began
 * @param nextLevelXpThreshold      total XP required to reach the next level
 * @param maxLevelReached           whether the player is at the maximum level
 */
public record ProgressionView(
        int level,
        int currentLevelXp,
        int xpRequiredForNextLevel,
        long totalXp,
        long currentLevelXpThreshold,
        long nextLevelXpThreshold,
        boolean maxLevelReached,
        List<String> unlockedCategories
) {
}
