package com.cenk.valocase.battle.service;

import org.springframework.stereotype.Component;

/**
 * Decides the outcome of a battle from per-participant totals.
 *
 * <p>A battle is a <em>draw</em> when every participant ends on the exact same
 * total VP. A draw has no winner at all: nothing is granted and each real player
 * gets their entry cost back. A partial tie is not a draw — if two of four
 * participants share the top total the normal winner selection still applies.
 *
 * <p>When there is a winner, the highest total VP wins; ties are broken by the
 * lowest participant index (so the user at index 0 wins ties).
 */
@Component
public class BattleResolver {

    /**
     * Winner index reported for a draw. Deliberately outside the valid range so
     * it matches no participant / slot index anywhere (response mapping,
     * leaderboard and analytics queries all compare against real indexes).
     */
    public static final int DRAW_WINNER_INDEX = -1;

    /**
     * @param totals total VP per participant, indexed by participant index
     * @return true when there are at least 2 participants and all of them share
     *         the same total
     */
    public boolean isDraw(long[] totals) {
        if (totals.length < 2) {
            return false;
        }
        for (int i = 1; i < totals.length; i++) {
            if (totals[i] != totals[0]) {
                return false;
            }
        }
        return true;
    }

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
