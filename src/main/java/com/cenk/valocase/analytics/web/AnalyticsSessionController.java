package com.cenk.valocase.analytics.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.analytics.dto.SessionAckResponse;
import com.cenk.valocase.analytics.dto.SessionEndRequest;
import com.cenk.valocase.analytics.dto.SessionSignalRequest;
import com.cenk.valocase.analytics.dto.SessionStartRequest;
import com.cenk.valocase.analytics.service.ClientSessionService;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Precise client session lifecycle. Authenticated by the existing X-Guest-Token;
 * the account is resolved entirely from the token, never from the body. These
 * endpoints return no analytics data to the player.
 */
@RestController
@RequestMapping("/api/v1/analytics/session")
@RequiredArgsConstructor
public class AnalyticsSessionController {

    private final AccountService accountService;
    private final ClientSessionService clientSessionService;

    @PostMapping("/start")
    public SessionAckResponse start(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SessionStartRequest request) {
        Account account = accountService.resolveActiveAccount(guestToken);
        return clientSessionService.start(account.getId(), requireBody(request));
    }

    @PostMapping("/heartbeat")
    public SessionAckResponse heartbeat(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SessionSignalRequest request) {
        Account account = accountService.resolveActiveAccount(guestToken);
        return clientSessionService.heartbeat(account.getId(), requireBody(request));
    }

    @PostMapping("/pause")
    public SessionAckResponse pause(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SessionSignalRequest request) {
        Account account = accountService.resolveActiveAccount(guestToken);
        return clientSessionService.pause(account.getId(), requireBody(request));
    }

    @PostMapping("/resume")
    public SessionAckResponse resume(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SessionStartRequest request) {
        Account account = accountService.resolveActiveAccount(guestToken);
        return clientSessionService.resume(account.getId(), requireBody(request));
    }

    @PostMapping("/end")
    public SessionAckResponse end(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) SessionEndRequest request) {
        Account account = accountService.resolveActiveAccount(guestToken);
        return clientSessionService.end(account.getId(), requireBody(request));
    }

    private <T> T requireBody(T body) {
        if (body == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        return body;
    }
}
