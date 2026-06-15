package com.cenk.valocase.inventory.dto;

/**
 * Result of selling one inventory item.
 */
public record SellOneResponse(
        String skinId,
        long vpGained,
        long newVpBalance
) {
}
