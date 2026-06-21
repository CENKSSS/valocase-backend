package com.cenk.valocase.upgrade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

class UpgradeChanceCalculatorTest {

    /** Fixed-value RNG stub. */
    private static UpgradeChanceCalculator withRoll(double fixed) {
        return new UpgradeChanceCalculator(() -> fixed);
    }

    @Test
    void computesRatioChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        assertEquals(50.0, calc.computeChance(5000, 10000, false, 0), 0.0001);
    }

    @Test
    void clampsToGlobalMaxChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, capped to the 65% global maximum.
        assertEquals(UpgradeChanceCalculator.GLOBAL_MAX_CHANCE, calc.computeChance(9500, 10000, false, 0), 0.0001);
    }

    @Test
    void equalValueNonMeleeCappedAtGlobalMax() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        assertEquals(65.0, calc.computeChance(10000, 10000, false, 0), 0.0001);
    }

    @Test
    void meleeTargetCappedAtFivePercent() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        assertEquals(5.0, calc.computeChance(10000, 10000, true, 0), 0.0001);
    }

    @Test
    void multipleMeleeInputsHalveChanceBeforeGlobalCap() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 4000/10000 -> 40% raw, halved by multi-Melee input penalty -> 20%.
        assertEquals(20.0, calc.computeChance(4000, 10000, false, 2), 0.0001);
    }

    @Test
    void multipleMeleeInputsThenGlobalCap() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, halved -> 47.5%, under the 65% cap.
        assertEquals(47.5, calc.computeChance(9500, 10000, false, 2), 0.0001);
    }

    @Test
    void multipleMeleeInputsAndMeleeTargetStillCappedAtFive() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // Halved to 47.5%, then Melee-target cap forces 5%.
        assertEquals(5.0, calc.computeChance(9500, 10000, true, 3), 0.0001);
    }

    @Test
    void clampsToMinChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 1/10000 -> 0.01% raw, floored to 1%.
        assertEquals(UpgradeChanceCalculator.MIN_CHANCE, calc.computeChance(1, 10000, false, 0), 0.0001);
    }

    @Test
    void invalidTargetValueThrows422() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        ApiException ex = assertThrows(ApiException.class, () -> calc.computeChance(100, 0, false, 0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void rollSucceedsWhenRandomBelowChance() {
        // roll = 0.03 -> 3% < 5% chance -> success
        assertTrue(withRoll(0.03).roll(5.0));
    }

    @Test
    void rollFailsWhenRandomAboveChance() {
        // roll = 0.80 -> 80% not < 5% chance -> fail
        assertFalse(withRoll(0.80).roll(5.0));
    }
}
