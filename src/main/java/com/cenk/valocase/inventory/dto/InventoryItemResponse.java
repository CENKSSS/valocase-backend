package com.cenk.valocase.inventory.dto;

import java.time.Instant;

/**
 * One owned skin instance, flattened with its skin catalog details.
 */
public record InventoryItemResponse(
        String itemId,
        String skinId,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef,
        String source,
        Instant acquiredAt
) {
}
