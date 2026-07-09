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
 * <p>Total XP is the single source of truth; level is always derived from it via
 * {@link #LEVEL_THRESHOLDS}. A successful case opening grants
 * {@value #XP_PER_CASE_OPEN} XP. Level is capped at {@link #MAX_LEVEL}; XP earned
 * beyond the max-level threshold is preserved but does not raise the level.
 */
@Service
public class ProgressionService {

    /** XP granted per successful case opening. */
    public static final int XP_PER_CASE_OPEN = 5;

    /**
     * Cumulative total-XP required to reach each level, indexed by {@code level - 1}.
     * Level 1 = 0 XP up to level {@link #MAX_LEVEL}.
     */
    private static final long[] LEVEL_THRESHOLDS = {
            0,    // level 1
            40,   // level 2
            95,   // level 3
            160,  // level 4
            250,  // level 5
            350,  // level 6
            465,  // level 7
            610,  // level 8
            775,  // level 9
            860,  // level 10
            945,  // level 11
            1050, // level 12
            1155, // level 13
            1250, // level 14
            1350  // level 15
    };

    /** Highest attainable level for now. */
    public static final int MAX_LEVEL = LEVEL_THRESHOLDS.length;

    /** Level derived from a total-XP value, capped at {@link #MAX_LEVEL}. */
    public int levelForXp(long totalXp) {
        int level = 1;
        for (int i = 1; i < LEVEL_THRESHOLDS.length; i++) {
            if (totalXp >= LEVEL_THRESHOLDS[i]) {
                level = i + 1;
            } else {
                break;
            }
        }
        return level;
    }

    /** Level of an account, derived from its total XP (source of truth). */
    public int levelOf(Account account) {
        return levelForXp(account.getTotalXp());
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
     * Grants case-open XP to the account. Total XP is the source of truth; level
     * and within-level XP are recomputed from it. Mutates the account in place
     * (persisted by the caller's transaction) and returns the resulting delta.
     */
    public CaseOpenProgressionResponse grantCaseOpenXp(Account account, int xp) {
        int previousLevel = levelForXp(account.getTotalXp());

        long newTotalXp = account.getTotalXp() + xp;
        int newLevel = levelForXp(newTotalXp);
        applyDerivedFields(account, newTotalXp, newLevel);

        boolean leveledUp = newLevel > previousLevel;
        List<String> newlyUnlocked = categoriesUnlockedBetween(previousLevel, newLevel);

        LevelState state = levelState(newTotalXp, newLevel);
        return new CaseOpenProgressionResponse(
                newLevel,
                state.currentLevelXp(),
                state.xpRequiredForNextLevel(),
                newTotalXp,
                state.currentLevelXpThreshold(),
                state.nextLevelXpThreshold(),
                state.maxLevelReached(),
                xp,
                leveledUp,
                newlyUnlocked
        );
    }

    /** Progression snapshot for the startup/bootstrap and wallet responses. */
    public ProgressionView buildView(Account account) {
        long totalXp = account.getTotalXp();
        int level = levelForXp(totalXp);
        LevelState state = levelState(totalXp, level);
        return new ProgressionView(
                level,
                state.currentLevelXp(),
                state.xpRequiredForNextLevel(),
                totalXp,
                state.currentLevelXpThreshold(),
                state.nextLevelXpThreshold(),
                state.maxLevelReached(),
                unlockedCategories(level)
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

    private void applyDerivedFields(Account account, long totalXp, int level) {
        account.setTotalXp(totalXp);
        account.setLevel(level);
        account.setCurrentLevelXp((int) (totalXp - LEVEL_THRESHOLDS[level - 1]));
    }

    private LevelState levelState(long totalXp, int level) {
        long currentThreshold = LEVEL_THRESHOLDS[level - 1];
        boolean maxLevelReached = level >= MAX_LEVEL;
        long nextThreshold = maxLevelReached ? currentThreshold : LEVEL_THRESHOLDS[level];
        int currentLevelXp = (int) (totalXp - currentThreshold);
        int xpRequiredForNextLevel = maxLevelReached ? 0 : (int) (nextThreshold - currentThreshold);
        return new LevelState(
                currentLevelXp, xpRequiredForNextLevel, currentThreshold, nextThreshold, maxLevelReached);
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

    private record LevelState(
            int currentLevelXp,
            int xpRequiredForNextLevel,
            long currentLevelXpThreshold,
            long nextLevelXpThreshold,
            boolean maxLevelReached) {
    }
}
