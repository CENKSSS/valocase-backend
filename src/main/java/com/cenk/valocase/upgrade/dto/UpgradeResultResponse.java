package com.cenk.valocase.upgrade.dto;

import java.util.List;

/**
 * Server-authoritative result of an upgrade. Input items are always consumed;
 * targets are granted only on success (empty/null on failure).
 *
 * {@code targetSkinId} and {@code grantedInventoryItemId} hold the first target
 * for backward compatibility; the full lists carry every selected target.
 */
public record UpgradeResultResponse(
        String upgradeId,
        boolean success,
        double chance,
        List<String> consumedItemIds,
        String targetSkinId,
        List<String> targetSkinIds,
        String grantedInventoryItemId,
        List<String> grantedInventoryItemIds,
        double appliedAdBuffPercent
) {
}
