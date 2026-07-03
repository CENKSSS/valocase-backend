package com.cenk.valocase.market.dto;

/**
 * Request to buy a VP pack with diamonds. {@code offerId} must be one of the
 * server-defined VP exchange offer ids.
 */
public record MarketPurchaseRequest(
        String offerId
) {
}
