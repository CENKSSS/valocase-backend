package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Request to preview an upgrade without executing it. Mirrors the real upgrade
 * input: owned inventory item ids and a single target skin id.
 */
public record UpgradePreviewRequest(
        List<String> inputInventoryItemIds,
        String targetSkinId
) {
}
