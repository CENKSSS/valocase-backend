package com.cenk.valocase.inventory.dto;

/**
 * Result of a bulk sell (sell-all or sell-below-value).
 */
public record SellResultResponse(
        int soldCount,
        long totalVpGained,
        long newVpBalance
) {
}
