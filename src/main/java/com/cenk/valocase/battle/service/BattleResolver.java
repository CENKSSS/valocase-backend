package com.cenk.valocase.battle.service;

import org.springframework.stereotype.Component;

/**
 * Decides the outcome of a battle from per-participant totals.
 *
 * <p>A battle is a <em>draw</em> when two or more participants share the highest
 * total VP. The draw covers only those tied at the top: they have no winner
 * between them and each real player among them gets their entry back. Everyone
 * below the top simply lost, exactly as they would have without the tie — a tie
 * further down the table changes nothing, so {@code 900/500/500/300} is a normal
 * win for the first participant.
 *
 * <p>Bots count toward the top like any other participant, so two bots tying at
 * the top is still a draw with no winner. They have no wallet, so nothing is
 * credited to them; clients derive who drew from the totals themselves.
 *
 * <p>When the top is held by exactly one participant, that participant wins.
 * {@link #winningIndex(long[])} is only meaningful in that case.
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
     * Highest total among the participants — the value that decides both the
     * winner and who is covered by a draw.
     *
     * @param totals total VP per participant, indexed by participant index
     * @return the highest total, or {@link Long#MIN_VALUE} when there are none
     */
    public long topTotal(long[] totals) {
        long top = Long.MIN_VALUE;
        for (long total : totals) {
            if (total > top) {
                top = total;
            }
        }
        return top;
    }

    /**
     * @param totals total VP per participant, indexed by participant index
     * @return true when two or more participants share {@link #topTotal(long[])}
     */
    public boolean isDraw(long[] totals) {
        if (totals.length < 2) {
            return false;
        }
        long top = topTotal(totals);
        int atTop = 0;
        for (long total : totals) {
            if (total == top) {
                atTop++;
            }
        }
        return atTop >= 2;
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
