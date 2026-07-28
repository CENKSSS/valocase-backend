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
        assertTrue(resolver.isDraw(new long[]{700, 700, 700, 700}));
    }

    @Test
    void everyoneEqualAtZero_isDraw() {
        assertTrue(resolver.isDraw(new long[]{0, 0}));
    }

    @Test
    void twoSharingTheTop_isDraw() {
        // 900/900/500/300 -> the two at 900 draw; the rest simply lost.
        assertTrue(resolver.isDraw(new long[]{900, 900, 500, 300}));
        assertEquals(900, resolver.topTotal(new long[]{900, 900, 500, 300}));
    }

    @Test
    void threeSharingTheTop_isDraw() {
        assertTrue(resolver.isDraw(new long[]{900, 900, 900, 100}));
        assertEquals(900, resolver.topTotal(new long[]{900, 900, 900, 100}));
    }

    @Test
    void twoSharingTheTopInAThreeWayLobby_isDraw() {
        assertTrue(resolver.isDraw(new long[]{800, 800, 200}));
    }

    @Test
    void tieBelowTheTop_isNotDraw() {
        // 900/500/500/300 -> one clear winner; the tie at 500 is irrelevant.
        assertFalse(resolver.isDraw(new long[]{900, 500, 500, 300}));
        assertEquals(0, resolver.winningIndex(new long[]{900, 500, 500, 300}));
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

    @Test
    void topTotal_findsTheHighestWhereverItSits() {
        assertEquals(300, resolver.topTotal(new long[]{100, 300, 200}));
        assertEquals(100, resolver.topTotal(new long[]{100}));
        assertEquals(0, resolver.topTotal(new long[]{0, 0}));
    }
}
