package com.cenk.valocase.market.dto;

import java.util.List;

/**
 * The Market catalog the client renders: diamond -> VP exchange offers and the
 * display-only diamond packs. {@code diamondPacksComingSoon} signals that the
 * real-money diamond section is a placeholder.
 */
public record MarketCatalogResponse(
        List<VpExchangeOffer> vpOffers,
        List<DiamondPack> diamondPacks,
        boolean diamondPacksComingSoon
) {
}
