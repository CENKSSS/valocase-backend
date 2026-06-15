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
        assertEquals(50.0, calc.computeChance(5000, 10000), 0.0001);
    }

    @Test
    void clampsToMaxChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, clamped to 90%.
        assertEquals(UpgradeChanceCalculator.MAX_CHANCE, calc.computeChance(9500, 10000), 0.0001);
    }

    @Test
    void clampsToMinChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 1/10000 -> 0.01% raw, clamped to 1%.
        assertEquals(UpgradeChanceCalculator.MIN_CHANCE, calc.computeChance(1, 10000), 0.0001);
    }

    @Test
    void invalidTargetValueThrows422() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        ApiException ex = assertThrows(ApiException.class, () -> calc.computeChance(100, 0));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    @Test
    void rollSucceedsWhenRandomBelowChance() {
        // roll = 0.10 -> 10% < 50% chance -> success
        assertTrue(withRoll(0.10).roll(50.0));
    }

    @Test
    void rollFailsWhenRandomAboveChance() {
        // roll = 0.80 -> 80% not < 50% chance -> fail
        assertFalse(withRoll(0.80).roll(50.0));
    }
}
