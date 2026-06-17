package com.cenk.valocase.progression;

import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.progression.domain.CaseCategory;

/**
 * Thrown when a player tries to open a case from a category their level has not
 * unlocked yet. Carries the unlock info so the client can show what is needed.
 *
 * <p>Maps to HTTP 403; thrown before any VP is charged, any reward is rolled or
 * any XP is granted.
 */
public class CategoryLockedException extends ApiException {

    private final CaseCategory category;
    private final int requiredLevel;
    private final int currentLevel;

    public CategoryLockedException(CaseCategory category, int currentLevel) {
        super(HttpStatus.FORBIDDEN, String.format(
                "Case category %s is locked. Requires level %d, current level %d.",
                category.name(), category.getUnlockLevel(), currentLevel));
        this.category = category;
        this.requiredLevel = category.getUnlockLevel();
        this.currentLevel = currentLevel;
    }

    public CaseCategory getCategory() {
        return category;
    }

    public int getRequiredLevel() {
        return requiredLevel;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }
}
