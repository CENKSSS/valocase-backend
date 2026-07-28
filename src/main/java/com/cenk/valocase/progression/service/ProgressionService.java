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
 * {@value #XP_PER_CASE_OPEN} XP.
 *
 * <p><strong>There is no maximum level.</strong> The threshold table covers
 * levels 1..{@link #MAX_UNLOCK_LEVEL}, which is where the last case category
 * unlocks; past that a player keeps levelling every
 * {@value #XP_PER_LEVEL_BEYOND_TABLE} XP, gaining levels but unlocking nothing
 * new (everything is already open at {@link #MAX_UNLOCK_LEVEL}). Because there
 * is always a next level, {@code xpRequiredForNextLevel} is always positive and
 * {@code maxLevelReached} is always false.
 *
 * <p><strong>{@code accounts.level} and {@code accounts.current_level_xp} are
 * caches, not inputs.</strong> Both are recomputed from {@code total_xp} on every
 * read and rewritten on every grant, so editing them by hand — in SQL or
 * anywhere else — changes nothing the player sees and is silently overwritten by
 * the next XP grant. To move a player to level N, set {@code total_xp} to that
 * level's threshold (see {@link #totalXpForLevel(int)}) and let the derived
 * columns follow:
 *
 * <pre>{@code
 * -- level 10 = 860 total XP (thresholds below)
 * UPDATE accounts SET total_xp = 860, level = 10, current_level_xp = 0
 * WHERE id = '<account-id>';
 * }</pre>
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

    /**
     * Last level covered by {@link #LEVEL_THRESHOLDS} and the last level at which
     * anything unlocks — {@link CaseCategory#MELEE}, the final category, opens
     * here. Levelling does not stop at this point; nothing new simply unlocks
     * beyond it.
     */
    public static final int MAX_UNLOCK_LEVEL = LEVEL_THRESHOLDS.length;

    /**
     * Flat XP cost of every level past {@link #MAX_UNLOCK_LEVEL}, continuing the
     * roughly 100-XP steps the table ends on. There is no maximum level: a player
     * keeps levelling for as long as they keep earning XP.
     */
    public static final long XP_PER_LEVEL_BEYOND_TABLE = 100L;

    /** Level derived from a total-XP value. Uncapped past {@link #MAX_UNLOCK_LEVEL}. */
    public int levelForXp(long totalXp) {
        long lastTableThreshold = LEVEL_THRESHOLDS[MAX_UNLOCK_LEVEL - 1];
        if (totalXp >= lastTableThreshold) {
            // Long arithmetic then a clamp, so even a corrupted total_xp cannot
            // overflow the int level.
            long beyond = (totalXp - lastTableThreshold) / XP_PER_LEVEL_BEYOND_TABLE;
            return (int) Math.min(MAX_UNLOCK_LEVEL + beyond, Integer.MAX_VALUE);
        }
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

    /** Cumulative total XP required to reach {@code level}, for any level ≥ 1. */
    private static long thresholdForLevel(int level) {
        if (level <= MAX_UNLOCK_LEVEL) {
            return LEVEL_THRESHOLDS[level - 1];
        }
        return LEVEL_THRESHOLDS[MAX_UNLOCK_LEVEL - 1]
                + (long) (level - MAX_UNLOCK_LEVEL) * XP_PER_LEVEL_BEYOND_TABLE;
    }

    /**
     * XP span of {@code level} — what it costs to reach the next one. Always
     * positive. Expressed as a span rather than "threshold of level + 1" so the
     * arithmetic cannot overflow at the very top of the int range.
     */
    private static long levelSpan(int level) {
        if (level < MAX_UNLOCK_LEVEL) {
            return LEVEL_THRESHOLDS[level] - LEVEL_THRESHOLDS[level - 1];
        }
        return XP_PER_LEVEL_BEYOND_TABLE;
    }

    /** Level of an account, derived from its total XP (source of truth). */
    public int levelOf(Account account) {
        return levelForXp(account.getTotalXp());
    }

    /**
     * Total XP a player must hold to sit exactly at the start of {@code level}.
     * This is the value to write into {@code total_xp} when moving an account to
     * a level by hand; writing the level column alone has no effect. Levels above
     * {@link #MAX_UNLOCK_LEVEL} are valid — they just unlock nothing new.
     *
     * @throws IllegalArgumentException if the level is below 1
     */
    public long totalXpForLevel(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be at least 1: " + level);
        }
        return thresholdForLevel(level);
    }

    /**
     * Rewrites the cached {@code level} / {@code current_level_xp} columns from
     * the account's total XP without granting anything. Use after a manual
     * {@code total_xp} edit to make the stored row agree with what the player
     * sees; every read already derives these values, so this only keeps the
     * database itself honest.
     */
    public void resyncDerivedFields(Account account) {
        long totalXp = account.getTotalXp();
        applyDerivedFields(account, totalXp, levelForXp(totalXp));
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
        account.setCurrentLevelXp((int) (totalXp - thresholdForLevel(level)));
    }

    private LevelState levelState(long totalXp, int level) {
        long currentThreshold = thresholdForLevel(level);
        long nextThreshold = currentThreshold + levelSpan(level);
        int currentLevelXp = (int) (totalXp - currentThreshold);
        // There is no maximum level, so there is always a next one and the XP
        // needed for it is always positive — a client dividing by this value can
        // never hit a zero.
        int xpRequiredForNextLevel = (int) (nextThreshold - currentThreshold);
        return new LevelState(currentLevelXp, xpRequiredForNextLevel, currentThreshold, nextThreshold, false);
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
