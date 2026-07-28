package com.cenk.valocase.progression.dto;

import java.util.List;

/**
 * Progression delta returned after a successful case opening.
 *
 * @param currentLevelXp          XP progress within the current level
 * @param xpRequiredForNextLevel  XP span of the current level; always positive,
 *                                since there is no maximum level
 * @param currentLevelXpThreshold total XP at which the current level began
 * @param nextLevelXpThreshold    total XP required to reach the next level
 * @param maxLevelReached         always false — levelling never stops. Kept so
 *                                existing clients still parse the response
 * @param unlockedCategories      categories newly unlocked by this open (empty
 *                                when no level threshold was crossed)
 */
public record CaseOpenProgressionResponse(
        int level,
        int currentLevelXp,
        int xpRequiredForNextLevel,
        long totalXp,
        long currentLevelXpThreshold,
        long nextLevelXpThreshold,
        boolean maxLevelReached,
        int xpGranted,
        boolean leveledUp,
        List<String> unlockedCategories
) {
}
