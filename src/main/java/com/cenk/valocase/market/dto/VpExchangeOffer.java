package com.cenk.valocase.market.dto;

/**
 * One "buy VP with diamonds" offer. {@code diamondCost} is the server-authoritative
 * price; the client must never send its own cost.
 */
public record VpExchangeOffer(
        String id,
        long vpAmount,
        long diamondCost
) {
}
