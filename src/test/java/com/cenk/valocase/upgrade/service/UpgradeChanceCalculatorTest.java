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
    void computesRatioChanceWithHouseFactor() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 5000/10000 -> 50% raw, * 0.85 house factor -> 42.5%.
        assertEquals(42.5, calc.computeChance(5000, 10000), 0.0001);
    }

    @Test
    void houseFactorIsFifteenPercentHarder() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // Old 40% (4000/10000) -> 34%, old 20% -> 17%, old 10% -> 8.5%.
        assertEquals(34.0, calc.computeChance(4000, 10000), 0.0001);
        assertEquals(17.0, calc.computeChance(2000, 10000), 0.0001);
        assertEquals(8.5, calc.computeChance(1000, 10000), 0.0001);
    }

    @Test
    void houseFactorConstantIsZeroPointEightFive() {
        assertEquals(0.85, UpgradeChanceCalculator.HOUSE_FACTOR, 0.0001);
    }

    @Test
    void clampsToGlobalMaxChance() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 95% raw, * 0.85 = 80.75%, capped to the 55.25% global maximum.
        assertEquals(UpgradeChanceCalculator.GLOBAL_MAX_CHANCE, calc.computeChance(9500, 10000), 0.0001);
    }

    @Test
    void normalCapIsFiftyFivePointTwoFive() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9999/10000 -> 99.99% raw, * 0.85, still capped at 55.25% without a boost.
        assertEquals(55.25, UpgradeChanceCalculator.GLOBAL_MAX_CHANCE, 0.0001);
        assertEquals(55.25, calc.computeChance(9999, 10000), 0.0001);
    }

    @Test
    void boostAddsFlatFivePercentagePoints() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 3000/10000 -> 30% raw, * 0.85 = 25.5% base, +5 boost -> 30.5% (additive, not a multiplier).
        assertEquals(30.5, calc.computeChance(3000, 10000, 5.0), 0.0001);
    }

    @Test
    void boostIsAdditiveNotMultiplier() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 5000/10000 -> 50% raw, * 0.85 = 42.5% base. Additive +5 -> 47.5%,
        // NOT 42.5 * 1.05 = 44.625 and NOT 42.5 * 5.
        assertEquals(47.5, calc.computeChance(5000, 10000, 5.0), 0.0001);
    }

    @Test
    void boostedCapIsSixtyPointTwoFive() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // 9500/10000 -> 80.75% after house factor, base capped at 55.25, +5 boost -> 60.25 (boosted ceiling).
        assertEquals(60.25, calc.computeChance(9500, 10000, 5.0), 0.0001);
    }

    @Test
    void boostedChanceNeverExceedsSixtyPointTwoFive() {
        UpgradeChanceCalculator calc = withRoll(0.0);
        // Even at full ratio the boosted result is exactly 60.25, never above.
        assertEquals(60.25, calc.computeChance(10000, 10000, 5.0), 0.0001);
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
