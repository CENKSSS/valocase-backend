package com.cenk.valocase.account.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.service.AccountService;

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
}
