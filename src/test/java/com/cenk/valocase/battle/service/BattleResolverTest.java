package com.cenk.valocase.battle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
