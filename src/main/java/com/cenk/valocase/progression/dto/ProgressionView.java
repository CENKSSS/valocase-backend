package com.cenk.valocase.progression.dto;

import java.util.List;

/**
 * Player progression snapshot, exposed on the startup/bootstrap response so the
 * client can render level, XP bar and unlocked categories on launch.
 */
public record ProgressionView(
        int level,
        int currentLevelXp,
        int xpRequiredForNextLevel,
        long totalXp,
        List<String> unlockedCategories
) {
}
