package com.cenk.valocase.upgrade.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Computes upgrade success chance and performs the server-side roll. The returned
 * chance is the single source of truth: it is both the value sent to the client
 * and the value rolled against, so the displayed and actual chances always match.
 *
 * base = (inputValue / targetValue) * 100 * houseFactor. More than one Melee
 * input halves it. The result is then capped at the global maximum, capped again
 * at the far stricter Melee-target maximum when the target is Melee, and finally
 * floored at the minimum.
 */
@Component
@RequiredArgsConstructor
public class UpgradeChanceCalculator {

    public static final double HOUSE_FACTOR = 1.0;
    public static final double MIN_CHANCE = 1.0;
    public static final double GLOBAL_MAX_CHANCE = 65.0;
    public static final double MELEE_TARGET_MAX_CHANCE = 5.0;
    public static final double MULTI_MELEE_INPUT_FACTOR = 0.5;

    private final UpgradeRng rng;

    /** @return the final success chance as a percentage, rounded to 2 decimals. */
    public double computeChance(long inputValue, long targetValue, boolean targetIsMelee, int meleeInputCount) {
        if (targetValue <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid target value for upgrade");
        }
        double chance = ((double) inputValue / (double) targetValue) * 100.0 * HOUSE_FACTOR;
        if (meleeInputCount > 1) {
            chance *= MULTI_MELEE_INPUT_FACTOR;
        }
        chance = Math.min(chance, GLOBAL_MAX_CHANCE);
        if (targetIsMelee) {
            chance = Math.min(chance, MELEE_TARGET_MAX_CHANCE);
        }
        chance = Math.max(chance, MIN_CHANCE);
        return Math.round(chance * 100.0) / 100.0;
    }

    /** Rolls against a chance percentage; success when the roll falls under it. */
    public boolean roll(double chance) {
        return rng.nextDouble() * 100.0 < chance;
    }
}
