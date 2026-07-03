package com.cenk.valocase.market.dto;

/**
 * Result of a diamond -> VP exchange, carrying the updated wallet balances so the
 * client can refresh its top bar without a second call.
 */
public record MarketPurchaseResponse(
        String offerId,
        long vpGranted,
        long diamondCost,
        long vpBalance,
        long diamondBalance
) {
}
