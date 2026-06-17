package com.cenk.valocase.wallet.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.progression.dto.ProgressionView;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class WalletController {

    private final AccountService accountService;
    private final WalletService walletService;
    private final ProgressionService progressionService;

    /**
     * Returns the current VP balance and player progression for the guest
     * identified by the X-Guest-Token header. Called by the client on startup.
     */
    @GetMapping("/wallet")
    public WalletResponse getWallet(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        WalletResponse wallet = walletService.getWalletForAccount(account.getId());
        ProgressionView progression = progressionService.buildView(account);
        return new WalletResponse(
                wallet.accountId(), wallet.vpBalance(), wallet.updatedAt(), progression);
    }
}
