package com.cenk.valocase.adreward.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.adreward.domain.AdRewardType;
import com.cenk.valocase.adreward.dto.AdRewardClaimRequest;
import com.cenk.valocase.adreward.dto.AdRewardClaimResponse;
import com.cenk.valocase.adreward.dto.AdRewardStatusResponse;
import com.cenk.valocase.adreward.service.AdRewardService;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/ads/rewards")
@RequiredArgsConstructor
public class AdRewardController {

    private final AccountService accountService;
    private final AdRewardService adRewardService;

    /** Availability, remaining-today, cooldown per placement plus the active upgrade buff. */
    @GetMapping("/status")
    public AdRewardStatusResponse getStatus(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return adRewardService.getStatus(account.getId());
    }

    /** Grants the server-authoritative reward for a completed rewarded ad. */
    @PostMapping("/claim")
    public AdRewardClaimResponse claim(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) AdRewardClaimRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body with rewardType and adToken is required");
        }
        return adRewardService.claim(account.getId(), parseRewardType(request.rewardType()), request.adToken());
    }

    private static AdRewardType parseRewardType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "rewardType is required");
        }
        try {
            return AdRewardType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown rewardType: " + raw);
        }
    }
}
