package com.cenk.valocase.upgrade.service;

/**
 * Randomness seam for upgrade rolls, so the outcome can be made deterministic in
 * tests.
 */
public interface UpgradeRng {

    /** @return a value in the range [0, 1). */
    double nextDouble();
}
