package com.cenk.valocase.progression.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.dto.CaseOpenProgressionResponse;

class ProgressionServiceTest {

    private final ProgressionService service = new ProgressionService();

    private static Account account(int level, int currentLevelXp, long totalXp) {
        Account account = new Account();
        account.setLevel(level);
        account.setCurrentLevelXp(currentLevelXp);
        account.setTotalXp(totalXp);
        return account;
    }

    @Test
    void newAccount_startsAtLevel1WithZeroXp() {
        Account account = new Account();
        assertEquals(1, account.getLevel());
        assertEquals(0, account.getCurrentLevelXp());
        assertEquals(0L, account.getTotalXp());
        assertEquals(20, service.getRequiredXpForNextLevel());
    }

    @Test
    void grantCaseOpenXp_adds5Xp() {
        Account account = account(1, 0, 0L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, ProgressionService.XP_PER_CASE_OPEN);

        assertEquals(1, account.getLevel());
        assertEquals(5, account.getCurrentLevelXp());
        assertEquals(5L, account.getTotalXp());
        assertEquals(5, result.xpGranted());
        assertFalse(result.leveledUp());
        assertTrue(result.unlockedCategories().isEmpty());
    }

    @Test
    void levelUp_keepsLeftoverXp() {
        // Level 1 with 18/20, +5 XP -> level 2 with 3/20.
        Account account = account(1, 18, 18L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(2, account.getLevel());
        assertEquals(3, account.getCurrentLevelXp());
        assertEquals(23L, account.getTotalXp());
        assertTrue(result.leveledUp());
    }

    @Test
    void levelUp_reportsNewlyUnlockedCategories() {
        // Crossing into level 3 unlocks Ghost.
        Account account = account(2, 18, 38L);

        CaseOpenProgressionResponse result = service.grantCaseOpenXp(account, 5);

        assertEquals(3, account.getLevel());
        assertTrue(result.leveledUp());
        assertEquals(java.util.List.of("GHOST"), result.unlockedCategories());
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
