package com.cenk.valocase.caseopening.web;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.caseopening.dto.OpenCaseResultResponse;
import com.cenk.valocase.caseopening.service.CaseOpeningService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CaseOpeningController {

    private final AccountService accountService;
    private final CaseOpeningService caseOpeningService;

    /**
     * Opens a case for the guest identified by the X-Guest-Token header:
     * debits the price and grants one weighted-random skin, atomically.
     */
    @PostMapping("/cases/{caseId}/open")
    public OpenCaseResultResponse openCase(
            @PathVariable String caseId,
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        Account account = accountService.requireAccountByToken(guestToken);
        return caseOpeningService.open(account.getId(), caseId);
    }
}
