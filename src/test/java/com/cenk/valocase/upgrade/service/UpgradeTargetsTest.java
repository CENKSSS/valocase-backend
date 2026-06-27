package com.cenk.valocase.upgrade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

class UpgradeTargetsTest {

    @Test
    void singleTargetSkinId_used() {
        assertEquals("skin_t", UpgradeTargets.normalizeSingleTarget(null, "skin_t"));
    }

    @Test
    void singleEntryTargetSkinIds_used() {
        assertEquals("skin_t", UpgradeTargets.normalizeSingleTarget(List.of("skin_t"), null));
    }

    @Test
    void bothPresentAndMatching_used() {
        assertEquals("skin_t", UpgradeTargets.normalizeSingleTarget(List.of("skin_t"), "skin_t"));
    }

    @Test
    void multipleTargetSkinIds_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> UpgradeTargets.normalizeSingleTarget(List.of("skin_t1", "skin_t2"), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void conflictingSingleAndList_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> UpgradeTargets.normalizeSingleTarget(List.of("skin_t1"), "skin_t2"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void emptyListFallsBackToSingle() {
        assertEquals("skin_t", UpgradeTargets.normalizeSingleTarget(List.of(), "skin_t"));
    }

    @Test
    void nullListAndNullSingle_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> UpgradeTargets.normalizeSingleTarget(null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }

    @Test
    void blankSingleEntryList_rejected() {
        ApiException ex = assertThrows(ApiException.class,
                () -> UpgradeTargets.normalizeSingleTarget(Arrays.asList("  "), null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
    }
}
