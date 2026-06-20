package com.cenk.valocase.account.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.dto.AccountProfileResponse;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.dto.UpdateDisplayNameRequest;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    /**
     * Creates a new guest account with a fresh wallet and starting VP.
     * Returns the guestToken the client must persist and send back.
     */
    @PostMapping("/guest")
    @ResponseStatus(HttpStatus.CREATED)
    public GuestRegisterResponse registerGuest() {
        return accountService.registerGuest();
    }

    /** Saves the player's chosen nickname as the account display name. */
    @PutMapping("/account/display-name")
    public AccountProfileResponse updateDisplayName(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) UpdateDisplayNameRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body with displayName is required");
        }
        return accountService.updateDisplayName(account, request.displayName());
    }
}
