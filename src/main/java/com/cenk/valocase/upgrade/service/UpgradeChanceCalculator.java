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
 * base = (inputValue / targetValue) * 100 * houseFactor, capped at the global
 * maximum of 65%. The Upgrade +5 ad boost adds a flat +5 percentage points on top
 * of the capped base, raising the ceiling to 70%. The result is finally floored at
 * the minimum. There are no weapon- or rarity-specific caps.
 */
@Component
@RequiredArgsConstructor
public class UpgradeChanceCalculator {

    public static final double HOUSE_FACTOR = 1.0;
    public static final double MIN_CHANCE = 1.0;

    /** Normal upgrade ceiling, with no ad boost active. */
    public static final double GLOBAL_MAX_CHANCE = 65.0;

    private final UpgradeRng rng;

    /** @return the final success chance as a percentage, rounded to 2 decimals. */
    public double computeChance(long inputValue, long targetValue) {
        return computeChance(inputValue, targetValue, 0.0);
    }

    /**
     * Computes the success chance with an optional additive ad-boost bonus.
     *
     * <p>The base ratio chance is capped at {@link #GLOBAL_MAX_CHANCE} (65%) first,
     * then {@code bonusPercent} is added on top as flat percentage points. With the
     * Upgrade +5 boost ({@code bonusPercent == 5}) this yields a maximum of 70%.
     * The bonus is additive, never a multiplier.
     *
     * @return the final success chance as a percentage, rounded to 2 decimals.
     */
    public double computeChance(long inputValue, long targetValue, double bonusPercent) {
        if (targetValue <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid target value for upgrade");
        }
        double base = ((double) inputValue / (double) targetValue) * 100.0 * HOUSE_FACTOR;
        // Global cap applies to the base before any boost: normal chance never exceeds 65%.
        base = Math.min(base, GLOBAL_MAX_CHANCE);
        double chance = base;
        if (bonusPercent > 0.0) {
            // +5 boost is additive: base (<= 65) + 5 -> max 70, never exceeding 65 + bonus.
            chance = Math.min(base + bonusPercent, GLOBAL_MAX_CHANCE + bonusPercent);
        }
        chance = Math.max(chance, MIN_CHANCE);
        return Math.round(chance * 100.0) / 100.0;
    }

    /** Rolls against a chance percentage; success when the roll falls under it. */
    public boolean roll(double chance) {
        return rng.nextDouble() * 100.0 < chance;
    }
}
