package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Server-authoritative result of an upgrade. Input items are always consumed;
 * grantedInventoryItemId is set only on success, null on failure.
 */
public record UpgradeResultResponse(
        String upgradeId,
        boolean success,
        double chance,
        List<String> consumedItemIds,
        String targetSkinId,
        String grantedInventoryItemId
) {
}
