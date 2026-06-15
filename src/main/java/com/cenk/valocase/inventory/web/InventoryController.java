package com.cenk.valocase.inventory.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.dto.InventoryResponse;
import com.cenk.valocase.inventory.dto.SellBelowValueRequest;
import com.cenk.valocase.inventory.dto.SellOneRequest;
import com.cenk.valocase.inventory.dto.SellOneResponse;
import com.cenk.valocase.inventory.dto.SellResultResponse;
import com.cenk.valocase.inventory.service.InventoryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class InventoryController {

    private final AccountService accountService;
    private final InventoryService inventoryService;

    /**
     * Returns all owned skin instances for the guest identified by the
     * X-Guest-Token header, newest first.
     */
    @GetMapping("/inventory")
    public InventoryResponse getInventory(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return inventoryService.getInventory(account.getId());
    }

    /** Sells one owned instance of the requested skin and credits its VP value. */
    @PostMapping("/inventory/sell")
    public SellOneResponse sellOne(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SellOneRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body with skinId is required");
        }
        return inventoryService.sellOne(account.getId(), request.skinId());
    }

    /** Sells the entire inventory and credits the total VP value. */
    @PostMapping("/inventory/sell-all")
    public SellResultResponse sellAll(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return inventoryService.sellAll(account.getId());
    }

    /** Sells every owned item whose catalog vpValue is at most maxVpValue. */
    @PostMapping("/inventory/sell-below-value")
    public SellResultResponse sellBelowValue(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SellBelowValueRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body with maxVpValue is required");
        }
        return inventoryService.sellBelowValue(account.getId(), request.maxVpValue());
    }
}
