package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Request to preview an upgrade without executing it. Mirrors the real upgrade
 * input: owned inventory item ids and a single target skin.
 *
 * The target may be sent as the legacy {@code targetSkinId} or, for symmetry with
 * the execute request, as a single-entry {@code targetSkinIds}; both are normalized
 * to exactly one target server-side.
 */
public record UpgradePreviewRequest(
        List<String> inputInventoryItemIds,
        String targetSkinId,
        List<String> targetSkinIds
) {
}
