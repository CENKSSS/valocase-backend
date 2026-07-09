package com.cenk.valocase.progression.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(15, service.levelForXp(5000));
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
    void atMaxLevel_preservesXpAndReportsMaxLevel() {
        Account account = account(1350L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(15, account.getLevel());
        assertEquals(1355L, account.getTotalXp());
        assertTrue(result.maxLevelReached());
        assertEquals(0, result.xpRequiredForNextLevel());
        assertFalse(result.leveledUp());
        assertEquals(1350L, result.currentLevelXpThreshold());
        assertEquals(1350L, result.nextLevelXpThreshold());
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
    void categoryInferredFromCaseIdPrefix() {
        assertEquals(CaseCategory.CLASSIC, CaseCategory.fromCaseId("classic_basic").orElseThrow());
        assertEquals(CaseCategory.GHOST, CaseCategory.fromCaseId("ghost_radiant").orElseThrow());
        assertEquals(CaseCategory.BULLDOG, CaseCategory.fromCaseId("bulldog_arcane").orElseThrow());
        assertEquals(CaseCategory.VANDAL, CaseCategory.fromCaseId("vandal_basic").orElseThrow());
        assertEquals(CaseCategory.MELEE, CaseCategory.fromCaseId("melee_case").orElseThrow());
        assertTrue(CaseCategory.fromCaseId("unknown_case").isEmpty());
    }
}
