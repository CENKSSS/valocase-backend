package com.cenk.valocase.upgrade.service;

import java.util.List;

import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

/**
 * Normalizes the simplified upgrade contract shared by preview, execute, and the
 * +5 ad boost context. The new contract allows only a single target: clients may
 * still send the legacy {@code targetSkinId} or the {@code targetSkinIds[]} list,
 * but both shapes are collapsed to exactly one target server-side so the client
 * can never bypass the rule.
 */
public final class UpgradeTargets {

    /** Maximum number of input inventory items a single upgrade may consume. */
    public static final int MAX_INPUT_ITEMS = 4;

    private UpgradeTargets() {
    }

    /**
     * Collapses {@code targetSkinIds[]} and {@code targetSkinId} into the single
     * target the upgrade contract now requires.
     *
     * <ul>
     *   <li>{@code targetSkinIds[]} with more than one entry is rejected.</li>
     *   <li>If both shapes are present they must reference the same single target.</li>
     *   <li>A lone {@code targetSkinId} or a single-entry {@code targetSkinIds[]} is used.</li>
     * </ul>
     *
     * @throws ApiException 400 when the selection cannot be reduced to exactly one target.
     */
    public static String normalizeSingleTarget(List<String> targetSkinIds, String targetSkinId) {
        String single = (targetSkinId != null && !targetSkinId.isBlank()) ? targetSkinId.trim() : null;

        if (targetSkinIds != null && !targetSkinIds.isEmpty()) {
            if (targetSkinIds.size() > 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Exactly one target skin is allowed");
            }
            String fromList = targetSkinIds.get(0);
            if (fromList == null || fromList.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds contains a blank id");
            }
            String normalized = fromList.trim();
            if (single != null && !single.equals(normalized)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "targetSkinId and targetSkinIds must reference the same single target");
            }
            return normalized;
        }

        if (single != null) {
            return single;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "A single targetSkinId is required");
    }
}
