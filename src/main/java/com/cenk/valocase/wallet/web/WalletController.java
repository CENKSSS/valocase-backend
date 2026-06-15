package com.cenk.valocase.wallet.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WalletController {

    private final AccountService accountService;
    private final WalletService walletService;

    /**
     * Returns the current VP balance for the guest identified by the
     * X-Guest-Token header.
     */
    @GetMapping("/wallet")
    public WalletResponse getWallet(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return walletService.getWalletForAccount(account.getId());
    }
}
