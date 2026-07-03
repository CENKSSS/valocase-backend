package com.cenk.valocase.market.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.market.dto.MarketCatalogResponse;
import com.cenk.valocase.market.dto.MarketPurchaseRequest;
import com.cenk.valocase.market.dto.MarketPurchaseResponse;
import com.cenk.valocase.market.service.MarketService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketController {

    private final AccountService accountService;
    private final MarketService marketService;

    /** The Market catalog: diamond -> VP offers and the display-only diamond packs. */
    @GetMapping("/catalog")
    public MarketCatalogResponse getCatalog(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        accountService.requireAccountByToken(guestToken);
        return marketService.getCatalog();
    }

    /** Buys a VP pack with diamonds. Diamond cost is verified server-side. */
    @PostMapping("/vp/purchase")
    public MarketPurchaseResponse purchaseVp(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) MarketPurchaseRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        String offerId = request != null ? request.offerId() : null;
        return marketService.purchaseVp(account.getId(), offerId);
    }
}
