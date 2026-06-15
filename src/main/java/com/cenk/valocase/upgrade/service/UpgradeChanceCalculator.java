package com.cenk.valocase.upgrade.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Computes upgrade success chance and performs the server-side roll.
 *
 * chance = (inputValue / targetValue) * 100 * houseFactor, clamped to
 * [minChance, maxChance]. Pure logic plus an injected RNG seam for testability.
 */
@Component
@RequiredArgsConstructor
public class UpgradeChanceCalculator {

    public static final double HOUSE_FACTOR = 1.0;
    public static final double MIN_CHANCE = 1.0;
    public static final double MAX_CHANCE = 90.0;

    private final UpgradeRng rng;

    /** @return the clamped success chance as a percentage, rounded to 2 decimals. */
    public double computeChance(long inputValue, long targetValue) {
        if (targetValue <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Invalid target value for upgrade");
        }
        double raw = ((double) inputValue / (double) targetValue) * 100.0 * HOUSE_FACTOR;
        double clamped = Math.min(MAX_CHANCE, Math.max(MIN_CHANCE, raw));
        return Math.round(clamped * 100.0) / 100.0;
    }

    /** Rolls against a chance percentage; success when the roll falls under it. */
    public boolean roll(double chance) {
        return rng.nextDouble() * 100.0 < chance;
    }
}
