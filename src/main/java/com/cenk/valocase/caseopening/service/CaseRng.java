package com.cenk.valocase.caseopening.service;

/**
 * Randomness seam for case rarity selection, so the roll can be made deterministic
 * in tests.
 */
public interface CaseRng {

    /** @return a value in the range [0, 1). */
    double nextDouble();
}
