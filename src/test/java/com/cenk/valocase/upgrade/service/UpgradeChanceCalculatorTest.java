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
    void clampsToGlobalMaxChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, capped to the 65% global maximum.
        assertEquals(UpgradeChanceCalculator.GLOBAL_MAX_CHANCE, calc.computeChance(9500, 10000), 0.0001);
    }

    @Test
    void normalChanceNeverExceedsSixtyFive() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9999/10000 -> 99.99% raw, still capped at 65% without a boost.
        assertEquals(65.0, calc.computeChance(9999, 10000), 0.0001);
    }

    @Test
    void meleeTargetNoLongerCapped() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // There is no longer a Melee-specific cap: a high-ratio upgrade hits the 65% global cap,
        // exactly like any non-Melee target (previously this was forced down to 5%).
        assertEquals(65.0, calc.computeChance(9000, 10000), 0.0001);
    }

    @Test
    void multipleMeleeInputsNoLongerPenalized() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 4000/10000 -> 40% raw, no multi-Melee halving applied anymore -> stays 40%.
        assertEquals(40.0, calc.computeChance(4000, 10000), 0.0001);
    }

    @Test
    void boostAddsFlatFivePercentagePoints() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 3000/10000 -> 30% base, +5 boost -> 35% (additive, not a multiplier).
        assertEquals(35.0, calc.computeChance(3000, 10000, 5.0), 0.0001);
    }

    @Test
    void boostIsAdditiveNotMultiplier() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 5000/10000 -> 50% base. Additive +5 -> 55%, NOT 50 * 1.05 = 52.5 and NOT 50 * 5.
        assertEquals(55.0, calc.computeChance(5000, 10000, 5.0), 0.0001);
    }

    @Test
    void boostedChanceCappedAtSeventy() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, base capped at 65, +5 boost -> 70 (boosted ceiling).
        assertEquals(70.0, calc.computeChance(9500, 10000, 5.0), 0.0001);
    }

    @Test
    void boostedChanceNeverExceedsSeventy() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // Even at full ratio the boosted result is exactly 70, never above.
        assertEquals(70.0, calc.computeChance(10000, 10000, 5.0), 0.0001);
    }

    @Test
    void clampsToMinChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 1/10000 -> 0.01% raw, floored to 1%.
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
        // roll = 0.03 -> 3% < 5% chance -> success
        assertTrue(withRoll(0.03).roll(5.0));
    }

    @Test
    void rollFailsWhenRandomAboveChance() {
        // roll = 0.80 -> 80% not < 5% chance -> fail
        assertFalse(withRoll(0.80).roll(5.0));
    }
}
