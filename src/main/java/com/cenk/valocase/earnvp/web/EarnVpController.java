package com.cenk.valocase.earnvp.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.dto.EarnVpClaimRequest;
import com.cenk.valocase.earnvp.dto.EarnVpClaimResponse;
import com.cenk.valocase.earnvp.dto.EarnVpSessionResponse;
import com.cenk.valocase.earnvp.service.EarnVpService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/earn-vp")
@RequiredArgsConstructor
public class EarnVpController {

    private final AccountService accountService;
    private final EarnVpService earnVpService;

    /**
     * Starts a server-timed earn session for the guest identified by the
     * X-Guest-Token header and returns the session id the claim must reference.
     */
    @PostMapping("/session/start")
    public EarnVpSessionResponse startSession(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return earnVpService.startSession(account.getId());
    }

    /**
     * Validates a started earn session and grants server-computed VP for the guest
     * identified by the X-Guest-Token header. Duration is taken from the server
     * session start, not from the request.
     */
    @PostMapping("/session/claim")
    public EarnVpClaimResponse claim(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @RequestBody(required = false) EarnVpClaimRequest request) {
        Account account = accountService.requireAccountByToken(guestToken);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Request body with tapCount and clientSessionId is required");
        }
        return earnVpService.claim(
                account.getId(), request.tapCount(),
                request.clientSessionId(), request.tapOffsetsMs());
    }
}
