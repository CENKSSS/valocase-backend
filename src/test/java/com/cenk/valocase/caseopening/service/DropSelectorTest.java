package com.cenk.valocase.caseopening.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.common.exception.ApiException;

class DropSelectorTest {

    private final DropSelector dropSelector = new DropSelector();

    private static CaseEntry entry(String skinId, int weight) {
        CaseEntry e = new CaseEntry();
        e.setCaseId("case_x");
        e.setSkinId(skinId);
        e.setWeight(weight);
        return e;
    }

    @Test
    void ignoresZeroWeightEntriesAndAlwaysPicksThePositiveOne() {
        CaseEntry winner = entry("b", 5);
        List<CaseEntry> pool = List.of(entry("a", 0), winner, entry("c", 0));

        // Deterministic regardless of RNG: only one entry has positive weight.
        for (int i = 0; i < 100; i++) {
            assertSame(winner, dropSelector.selectWeighted(pool));
        }
    }

    @Test
    void alwaysReturnsAnEntryFromTheCandidateSet() {
        CaseEntry a = entry("a", 1);
        CaseEntry b = entry("b", 3);
        List<CaseEntry> pool = List.of(a, b);

        for (int i = 0; i < 1000; i++) {
            CaseEntry picked = dropSelector.selectWeighted(pool);
            assertTrue(picked == a || picked == b);
        }
    }

    @Test
    void throwsServerErrorWhenNoPositiveWeight() {
        List<CaseEntry> pool = List.of(entry("a", 0), entry("b", -3));

        ApiException ex = assertThrows(ApiException.class, () -> dropSelector.selectWeighted(pool));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatus());
    }
}
