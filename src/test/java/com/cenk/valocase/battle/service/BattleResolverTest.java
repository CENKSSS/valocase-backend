package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BattleResolverTest {

    private final BattleResolver resolver = new BattleResolver();

    @Test
    void highestTotalWins() {
        assertEquals(2, resolver.winningIndex(new long[]{100, 200, 300}));
    }

    @Test
    void userWinsWhenHighest() {
        assertEquals(0, resolver.winningIndex(new long[]{500, 200, 300}));
    }

    @Test
    void tieGoesToLowestIndex() {
        // index 0 and 2 tie at 300 -> lowest index (0) wins.
        assertEquals(0, resolver.winningIndex(new long[]{300, 100, 300}));
    }

    @Test
    void tieBetweenBotsGoesToLowerBot() {
        // user low; bots 1 and 2 tie -> bot index 1 wins.
        assertEquals(1, resolver.winningIndex(new long[]{50, 300, 300}));
    }

    @Test
    void everyoneEqual_isDraw() {
        assertTrue(resolver.isDraw(new long[]{300, 300}));
        assertTrue(resolver.isDraw(new long[]{300, 300, 300, 300}));
    }

    @Test
    void everyoneEqualAtZero_isDraw() {
        assertTrue(resolver.isDraw(new long[]{0, 0}));
    }

    @Test
    void partialTieAtTheTop_isNotDraw() {
        // 2 of 4 share the top total -> normal winner selection, not a draw.
        assertFalse(resolver.isDraw(new long[]{5000, 3000, 5000, 3000}));
        assertEquals(0, resolver.winningIndex(new long[]{5000, 3000, 5000, 3000}));
    }

    @Test
    void differentTotals_isNotDraw() {
        assertFalse(resolver.isDraw(new long[]{100, 200, 300}));
    }

    @Test
    void singleParticipant_isNotDraw() {
        assertFalse(resolver.isDraw(new long[]{300}));
        assertFalse(resolver.isDraw(new long[]{}));
    }
}
