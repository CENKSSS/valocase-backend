package com.cenk.valocase.progression.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.dto.CaseOpenProgressionResponse;
import com.cenk.valocase.progression.dto.ProgressionView;

class ProgressionServiceTest {

    private final ProgressionService service = new ProgressionService();

    private static Account account(long totalXp) {
        Account account = new Account();
        account.setTotalXp(totalXp);
        return account;
    }

    @Test
    void levelForXp_matchesThresholdTableAtBoundaries() {
        assertEquals(1, service.levelForXp(0));
        assertEquals(1, service.levelForXp(39));
        assertEquals(2, service.levelForXp(40));
        assertEquals(3, service.levelForXp(95));
        assertEquals(8, service.levelForXp(774));
        assertEquals(9, service.levelForXp(775));
        assertEquals(14, service.levelForXp(1349));
        assertEquals(15, service.levelForXp(1350));
        // Past the table the level keeps climbing: 1350 + 36 x 100 XP = level 51.
        assertEquals(51, service.levelForXp(5000));
    }

    @Test
    void newAccount_startsAtLevel1WithZeroXp() {
        Account account = new Account();
        assertEquals(1, service.levelOf(account));
        assertEquals(0L, account.getTotalXp());
    }

    @Test
    void grantCaseOpenXp_adds5Xp() {
        Account account = account(0L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, ProgressionService.XP_PER_CASE_OPEN);

        assertEquals(1, account.getLevel());
        assertEquals(5, account.getCurrentLevelXp());
        assertEquals(5L, account.getTotalXp());
        assertEquals(5, result.xpGranted());
        assertEquals(40, result.xpRequiredForNextLevel());
        assertEquals(0L, result.currentLevelXpThreshold());
        assertEquals(40L, result.nextLevelXpThreshold());
        assertFalse(result.leveledUp());
        assertFalse(result.maxLevelReached());
        assertTrue(result.unlockedCategories().isEmpty());
    }

    @Test
    void levelUp_keepsLeftoverXpWithinNewLevel() {
        // Level 1 at 38 XP, +5 -> 43 total = level 2, 3 XP into the level.
        Account account = account(38L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(2, account.getLevel());
        assertEquals(3, account.getCurrentLevelXp());
        assertEquals(43L, account.getTotalXp());
        assertEquals(3, result.currentLevelXp());
        assertEquals(55, result.xpRequiredForNextLevel());
        assertEquals(40L, result.currentLevelXpThreshold());
        assertEquals(95L, result.nextLevelXpThreshold());
        assertTrue(result.leveledUp());
    }

    @Test
    void levelUp_reportsNewlyUnlockedCategories() {
        // 90 XP -> level 2; +5 = 95 -> level 3 unlocks Ghost.
        Account account = account(90L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(3, account.getLevel());
        assertTrue(result.leveledUp());
        assertEquals(java.util.List.of("GHOST"), result.unlockedCategories());
    }

    @Test
    void levellingContinuesPastTheLastUnlockLevel() {
        Account account = account(1350L); // exactly level 15

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(15, account.getLevel());
        assertEquals(1355L, account.getTotalXp());
        assertEquals(5, result.currentLevelXp());
        assertEquals(100, result.xpRequiredForNextLevel());
        assertEquals(1350L, result.currentLevelXpThreshold());
        assertEquals(1450L, result.nextLevelXpThreshold());
        assertFalse(result.leveledUp());
    }

    @Test
    void level16IsReachable_andUnlocksNothingNew() {
        Account account = account(1445L); // 5 XP short of level 16

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(16, account.getLevel());
        assertEquals(0, account.getCurrentLevelXp());
        assertTrue(result.leveledUp());
        // Everything is already open at 15, so passing it unlocks nothing.
        assertTrue(result.unlockedCategories().isEmpty());
    }

    @Test
    void levelKeepsClimbingWithTotalXp() {
        assertEquals(15, service.levelForXp(1350));
        assertEquals(15, service.levelForXp(1449));
        assertEquals(16, service.levelForXp(1450));
        assertEquals(25, service.levelForXp(2350));
        assertEquals(115, service.levelForXp(11350));
    }

    @Test
    void thereIsNoMaxLevel_soTheXpBarDivisorIsNeverZero() {
        // A zero here is what broke the client: an XP bar dividing by it blew up.
        long[] samples = {0, 39, 40, 1349, 1350, 1355, 1450, 50_000, Long.MAX_VALUE};
        for (long totalXp : samples) {
            ProgressionView view = service.buildView(account(totalXp));
            assertTrue(view.xpRequiredForNextLevel() > 0,
                    "xpRequiredForNextLevel must stay positive at totalXp " + totalXp);
            assertFalse(view.maxLevelReached(), "no level is ever the maximum, at totalXp " + totalXp);
        }
    }

    @Test
    void absurdTotalXpDoesNotOverflowTheLevel() {
        // A corrupted or hand-edited total_xp must not wrap the int level negative.
        assertTrue(service.levelForXp(Long.MAX_VALUE) > 0);
    }

    @Test
    void buildView_derivesLevelFromTotalXp() {
        ProgressionView view = service.buildView(account(775L));

        assertEquals(9, view.level());
        assertEquals(0, view.currentLevelXp());
        assertEquals(85, view.xpRequiredForNextLevel());
        assertEquals(775L, view.currentLevelXpThreshold());
        assertEquals(860L, view.nextLevelXpThreshold());
        assertFalse(view.maxLevelReached());
    }

    @Test
    void unlockLevels_areCorrect() {
        assertEquals(1, service.getUnlockLevelForCategory(CaseCategory.CLASSIC));
        assertEquals(3, service.getUnlockLevelForCategory(CaseCategory.GHOST));
        assertEquals(7, service.getUnlockLevelForCategory(CaseCategory.BULLDOG));
        assertEquals(9, service.getUnlockLevelForCategory(CaseCategory.VANDAL));
        assertEquals(15, service.getUnlockLevelForCategory(CaseCategory.MELEE));
    }

    @Test
    void isCategoryUnlocked_respectsLevelThresholds() {
        assertTrue(service.isCategoryUnlocked(1, CaseCategory.CLASSIC));
        assertFalse(service.isCategoryUnlocked(1, CaseCategory.GHOST));
        assertTrue(service.isCategoryUnlocked(3, CaseCategory.GHOST));
        assertFalse(service.isCategoryUnlocked(8, CaseCategory.VANDAL));
        assertTrue(service.isCategoryUnlocked(9, CaseCategory.VANDAL));
        assertFalse(service.isCategoryUnlocked(14, CaseCategory.MELEE));
        assertTrue(service.isCategoryUnlocked(15, CaseCategory.MELEE));
    }

    @Test
    void totalXpForLevel_isTheValueToWriteWhenSettingALevelByHand() {
        // Round-trip, including levels well past the last unlock level.
        for (int level = 1; level <= 40; level++) {
            long totalXp = service.totalXpForLevel(level);
            assertEquals(level, service.levelForXp(totalXp), "level " + level);
        }
        assertEquals(860L, service.totalXpForLevel(10));
        assertEquals(1350L, service.totalXpForLevel(15));
        assertEquals(1450L, service.totalXpForLevel(16));
    }

    @Test
    void totalXpForLevel_rejectsLevelsBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> service.totalXpForLevel(0));
        assertThrows(IllegalArgumentException.class, () -> service.totalXpForLevel(-3));
    }

    @Test
    void resyncDerivedFields_repairsAHandEditedLevelColumn() {
        // Someone set level = 12 in SQL but left total_xp alone: the row lied.
        Account account = account(250); // 250 total XP is level 5
        account.setLevel(12);
        account.setCurrentLevelXp(999);

        service.resyncDerivedFields(account);

        assertEquals(5, account.getLevel());
        assertEquals(0, account.getCurrentLevelXp());
        assertEquals(250L, account.getTotalXp()); // total XP is never invented
    }

    @Test
    void handEditedLevelColumn_doesNotChangeWhatThePlayerSees() {
        Account account = account(40); // level 2 by XP
        account.setLevel(15);          // hand-edited cache

        ProgressionView view = service.buildView(account);

        assertEquals(2, view.level());
        assertEquals(2, service.levelOf(account));
    }

    @Test
    void categoryInferredFromCaseIdPrefix() {
        assertEquals(CaseCategory.CLASSIC, CaseCategory.fromCaseId("classic_basic").orElseThrow());
        assertEquals(CaseCategory.GHOST, CaseCategory.fromCaseId("ghost_radiant").orElseThrow());
        assertEquals(CaseCategory.BULLDOG, CaseCategory.fromCaseId("bulldog_arcane").orElseThrow());
        assertEquals(CaseCategory.VANDAL, CaseCategory.fromCaseId("vandal_basic").orElseThrow());
        assertEquals(CaseCategory.MELEE, CaseCategory.fromCaseId("melee_case").orElseThrow());
        assertTrue(CaseCategory.fromCaseId("unknown_case").isEmpty());
    }
}
