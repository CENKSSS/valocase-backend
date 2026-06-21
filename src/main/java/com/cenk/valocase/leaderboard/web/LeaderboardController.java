package com.cenk.valocase.leaderboard.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.leaderboard.domain.LeaderboardType;
import com.cenk.valocase.leaderboard.dto.LeaderboardResponse;
import com.cenk.valocase.leaderboard.service.LeaderboardService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only Battle-screen leaderboards. Authenticated by the {@code X-Guest-Token}
 * header like the rest of the API; the token identifies the viewer whose own
 * standing is returned alongside the top entries.
 */
@RestController
@RequestMapping("/api/v1/leaderboards")
@RequiredArgsConstructor
public class LeaderboardController {

    private final AccountService accountService;
    private final LeaderboardService leaderboardService;

    @GetMapping
    public LeaderboardResponse get(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestParam("type") String type) {
        Account account = accountService.requireAccountByToken(guestToken);
        return leaderboardService.leaderboard(parseType(type), account);
    }

    private static LeaderboardType parseType(String type) {
        if (type == null || type.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "type is required");
        }
        try {
            return LeaderboardType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown leaderboard type: " + type);
        }
    }
}
