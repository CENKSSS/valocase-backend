package com.cenk.valocase.catalog.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.catalog.dto.CaseDetailResponse;
import com.cenk.valocase.catalog.dto.CaseSummaryResponse;
import com.cenk.valocase.catalog.dto.SkinResponse;
import com.cenk.valocase.catalog.service.CatalogService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only catalog endpoints. No auth in Phase 1.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/skins")
    public List<SkinResponse> getSkins() {
        return catalogService.getActiveSkins();
    }

    @GetMapping("/cases")
    public List<CaseSummaryResponse> getCases() {
        return catalogService.getActiveCases();
    }

    @GetMapping("/cases/{caseId}")
    public CaseDetailResponse getCase(@PathVariable String caseId) {
        return catalogService.getCaseDetail(caseId);
    }
}
