package com.cenk.valocase.progression.dto;

import java.util.List;

/**
 * Progression delta returned after a successful case opening.
 *
 * @param unlockedCategories categories that became newly unlocked by this open
 *                           (empty when no level threshold was crossed)
 */
public record CaseOpenProgressionResponse(
        int level,
        int currentLevelXp,
        int xpRequiredForNextLevel,
        long totalXp,
        int xpGranted,
        boolean leveledUp,
        List<String> unlockedCategories
) {
}
