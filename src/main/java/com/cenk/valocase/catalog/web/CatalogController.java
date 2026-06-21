package com.cenk.valocase.catalog.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.catalog.dto.CaseDetailResponse;
import com.cenk.valocase.catalog.dto.CaseSummaryResponse;
import com.cenk.valocase.catalog.dto.SkinResponse;
import com.cenk.valocase.catalog.service.CatalogService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only catalog endpoints. The case endpoints are optionally player-aware:
 * an X-Guest-Token header adds the viewer's unlock/affordability state.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    private final AccountService accountService;

    @GetMapping("/skins")
    public List<SkinResponse> getSkins() {
        return catalogService.getActiveSkins();
    }

    @GetMapping("/cases")
    public List<CaseSummaryResponse> getCases(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken) {
        return catalogService.getActiveCases(resolveViewer(guestToken));
    }

    @GetMapping("/cases/{caseId}")
    public CaseDetailResponse getCase(
            @RequestHeader(value = "X-Guest-Token", required = false) String guestToken,
            @PathVariable String caseId) {
        return catalogService.getCaseDetail(caseId, resolveViewer(guestToken));
    }

    private Account resolveViewer(String guestToken) {
        if (guestToken == null || guestToken.isBlank()) {
            return null;
        }
        return accountService.requireAccountByToken(guestToken);
    }
}
