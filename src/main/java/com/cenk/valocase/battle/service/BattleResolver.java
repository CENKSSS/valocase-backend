package com.cenk.valocase.battle.service;

import org.springframework.stereotype.Component;

/**
 * Decides the winning participant from per-participant totals. Highest total VP
 * wins; ties are broken by the lowest participant index (so the user at index 0
 * wins ties).
 */
@Component
public class BattleResolver {

    /**
     * @param totals total VP per participant, indexed by participant index
     * @return the winning participant index
     */
    public int winningIndex(long[] totals) {
        int best = 0;
        for (int i = 1; i < totals.length; i++) {
            // Strictly greater only, so equal totals keep the lower index.
            if (totals[i] > totals[best]) {
                best = i;
            }
        }
        return best;
    }
}
