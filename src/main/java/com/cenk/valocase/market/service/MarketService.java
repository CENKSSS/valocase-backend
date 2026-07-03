package com.cenk.valocase.market.service;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.market.dto.MarketCatalogResponse;
import com.cenk.valocase.market.dto.MarketPurchaseResponse;
import com.cenk.valocase.market.dto.VpExchangeOffer;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative Market operations. The diamond -> VP exchange debits
 * diamonds and credits VP atomically; diamond costs come from {@link MarketCatalog}
 * so the client can never dictate the price. Real-money diamond purchases are not
 * implemented yet and no diamonds are granted for them.
 */
@Service
@RequiredArgsConstructor
public class MarketService {

    public static final String REASON_VP_EXCHANGE = "MARKET_VP_EXCHANGE";

    private final MarketCatalog catalog;
    private final WalletService walletService;

    public MarketCatalogResponse getCatalog() {
        return new MarketCatalogResponse(catalog.vpOffers(), catalog.diamondPacks(), true);
    }

    @Transactional
    public MarketPurchaseResponse purchaseVp(UUID accountId, String rawOfferId) {
        if (rawOfferId == null || rawOfferId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "offerId is required");
        }
        VpExchangeOffer offer = catalog.findVpOffer(rawOfferId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Unknown VP offer: " + rawOfferId));

        walletService.debitDiamonds(accountId, offer.diamondCost());
        Wallet wallet = walletService.credit(accountId, offer.vpAmount(), REASON_VP_EXCHANGE, null);

        return new MarketPurchaseResponse(offer.id(), offer.vpAmount(), offer.diamondCost(),
                wallet.getVpBalance(), wallet.getDiamondBalance());
    }
}
