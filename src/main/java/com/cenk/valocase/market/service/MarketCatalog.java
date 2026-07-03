package com.cenk.valocase.market.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.cenk.valocase.market.dto.DiamondPack;
import com.cenk.valocase.market.dto.VpExchangeOffer;

/**
 * Single source of truth for Market pricing. Diamond costs and diamond pack sizes
 * live here, never on the client. Real-money diamond packs are display-only until
 * billing is integrated.
 */
@Component
public class MarketCatalog {

    private static final List<VpExchangeOffer> VP_OFFERS = List.of(
            new VpExchangeOffer("vp_1000", 1_000L, 20L),
            new VpExchangeOffer("vp_25000", 25_000L, 375L),
            new VpExchangeOffer("vp_50000", 50_000L, 700L),
            new VpExchangeOffer("vp_100000", 100_000L, 1_250L));

    private static final List<DiamondPack> DIAMOND_PACKS = List.of(
            new DiamondPack("diamond_100", 100L, "$0.99", null, true),
            new DiamondPack("diamond_550", 550L, "Coming soon", "Small bonus", true),
            new DiamondPack("diamond_1200", 1_200L, "Coming soon", "Medium pack", true),
            new DiamondPack("diamond_2500", 2_500L, "Coming soon", "Large pack", true));

    public List<VpExchangeOffer> vpOffers() {
        return VP_OFFERS;
    }

    public List<DiamondPack> diamondPacks() {
        return DIAMOND_PACKS;
    }

    public Optional<VpExchangeOffer> findVpOffer(String offerId) {
        return VP_OFFERS.stream().filter(o -> o.id().equals(offerId)).findFirst();
    }
}
