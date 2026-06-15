package com.cenk.valocase.upgrade.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.upgrade.dto.UpgradeRequest;
import com.cenk.valocase.upgrade.dto.UpgradeResultResponse;
import com.cenk.valocase.upgrade.service.UpgradeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UpgradeController {

    private final AccountService accountService;
    private final UpgradeService upgradeService;

    /**
     * Upgrades owned input items into a target skin for the guest identified by
     * the X-Guest-Token header. Server-authoritative: consumes inputs, rolls, and
     * grants the target on success — all atomically.
     */
    @PostMapping("/upgrade")
    public UpgradeResultResponse upgrade(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) UpgradeRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body with inputItemIds and targetSkinId is required");
        }
        return upgradeService.upgrade(account.getId(), request.inputItemIds(), request.targetSkinId());
    }
}
