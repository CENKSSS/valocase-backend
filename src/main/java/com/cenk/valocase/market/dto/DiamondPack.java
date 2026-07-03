package com.cenk.valocase.market.dto;

/**
 * One "buy diamonds with real money" pack, exposed as display-only catalog data.
 * Real-money purchase is not implemented yet, so {@code comingSoon} is always true
 * and no diamonds are granted for these.
 */
public record DiamondPack(
        String id,
        long diamonds,
        String priceUsdLabel,
        String bonusLabel,
        boolean comingSoon
) {
}
