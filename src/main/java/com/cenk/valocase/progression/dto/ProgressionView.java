package com.cenk.valocase.progression.dto;

import java.util.List;

/**
 * Player progression snapshot, exposed on the startup/bootstrap and wallet
 * responses so the client can render level, XP bar and unlocked categories.
 *
 * @param currentLevelXp            XP progress within the current level
 * @param xpRequiredForNextLevel    XP span of the current level; always positive,
 *                                  since there is no maximum level, so it is safe
 *                                  to divide by when drawing the XP bar
 * @param currentLevelXpThreshold   total XP at which the current level began
 * @param nextLevelXpThreshold      total XP required to reach the next level
 * @param maxLevelReached           always false — levelling never stops. Kept so
 *                                  existing clients still parse the response
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
