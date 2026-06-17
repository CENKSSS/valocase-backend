package com.cenk.valocase.progression.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.dto.CaseOpenProgressionResponse;
import com.cenk.valocase.progression.dto.ProgressionView;

/**
 * Server-authoritative player progression: level, XP and category unlocks.
 *
 * <p>Level requirement is a flat {@value #XP_PER_LEVEL} XP for now. A successful
 * case opening grants {@value #XP_PER_CASE_OPEN} XP. Leftover XP is preserved
 * across level ups.
 */
@Service
public class ProgressionService {

    /** Flat XP required to advance one level. */
    public static final int XP_PER_LEVEL = 20;

    /** XP granted per successful case opening. */
    public static final int XP_PER_CASE_OPEN = 5;

    /** Flat XP required to reach the next level (constant for now). */
    public int getRequiredXpForNextLevel() {
        return XP_PER_LEVEL;
    }

    /** Player level at which the given category unlocks. */
    public int getUnlockLevelForCategory(CaseCategory category) {
        return category.getUnlockLevel();
    }

    /** Whether a player at {@code accountLevel} may open cases of this category. */
    public boolean isCategoryUnlocked(int accountLevel, CaseCategory category) {
        return accountLevel >= category.getUnlockLevel();
    }

    /**
     * Grants case-open XP to the account, applying level ups and keeping any
     * leftover XP. Mutates the account in place (persisted by the caller's
     * transaction) and returns the resulting progression delta.
     */
    public CaseOpenProgressionResponse grantCaseOpenXp(Account account, int xp) {
        int previousLevel = account.getLevel();

        int levelXp = account.getCurrentLevelXp() + xp;
        int level = previousLevel;
        while (levelXp >= XP_PER_LEVEL) {
            levelXp -= XP_PER_LEVEL;
            level++;
        }

        account.setCurrentLevelXp(levelXp);
        account.setLevel(level);
        account.setTotalXp(account.getTotalXp() + xp);

        boolean leveledUp = level > previousLevel;
        List<String> newlyUnlocked = categoriesUnlockedBetween(previousLevel, level);

        return new CaseOpenProgressionResponse(
                account.getLevel(),
                account.getCurrentLevelXp(),
                getRequiredXpForNextLevel(),
                account.getTotalXp(),
                xp,
                leveledUp,
                newlyUnlocked
        );
    }

    /** Progression snapshot for the startup/bootstrap response. */
    public ProgressionView buildView(Account account) {
        return new ProgressionView(
                account.getLevel(),
                account.getCurrentLevelXp(),
                getRequiredXpForNextLevel(),
                account.getTotalXp(),
                unlockedCategories(account.getLevel())
        );
    }

    /** Names of every category unlocked at the given level. */
    public List<String> unlockedCategories(int level) {
        List<String> unlocked = new ArrayList<>();
        for (CaseCategory category : CaseCategory.values()) {
            if (isCategoryUnlocked(level, category)) {
                unlocked.add(category.name());
            }
        }
        return unlocked;
    }

    /** Categories whose unlock level falls in {@code (fromLevel, toLevel]}. */
    private List<String> categoriesUnlockedBetween(int fromLevel, int toLevel) {
        List<String> newlyUnlocked = new ArrayList<>();
        for (CaseCategory category : CaseCategory.values()) {
            int unlockLevel = category.getUnlockLevel();
            if (unlockLevel > fromLevel && unlockLevel <= toLevel) {
                newlyUnlocked.add(category.name());
            }
        }
        return newlyUnlocked;
    }
}
