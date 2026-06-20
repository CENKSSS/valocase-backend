package com.cenk.valocase.daily.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.daily.dto.DailyClaimResponse;
import com.cenk.valocase.daily.dto.DailyStatusResponse;
import com.cenk.valocase.daily.service.DailyRewardService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DailyRewardController {

    private final AccountService accountService;
    private final DailyRewardService dailyRewardService;

    /** Daily reward status (claimable, reward, cooldown) for the guest. */
    @GetMapping("/daily")
    public DailyStatusResponse getDaily(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return dailyRewardService.getStatus(account.getId());
    }

    /** Claims the daily reward (once per rolling 24h). */
    @PostMapping("/daily/claim")
    public DailyClaimResponse claimDaily(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return dailyRewardService.claim(account.getId());
    }
}
