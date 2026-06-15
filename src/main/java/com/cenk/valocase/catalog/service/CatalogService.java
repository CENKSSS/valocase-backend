package com.cenk.valocase.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.dto.CaseDetailResponse;
import com.cenk.valocase.catalog.dto.CaseDropResponse;
import com.cenk.valocase.catalog.dto.CaseSummaryResponse;
import com.cenk.valocase.catalog.dto.SkinResponse;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;

import lombok.RequiredArgsConstructor;

/**
 * Read-only catalog access for Phase 1: active skins, active cases, and a
 * single case with its drop pool. No write operations yet.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    private final SkinRepository skinRepository;
    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseEntryRepository caseEntryRepository;

    public List<SkinResponse> getActiveSkins() {
        return skinRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(CatalogService::toSkinResponse)
                .toList();
    }

    public List<CaseSummaryResponse> getActiveCases() {
        return caseDefinitionRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(CatalogService::toCaseSummary)
                .toList();
    }

    public CaseDetailResponse getCaseDetail(String caseId) {
        CaseDefinition caseDef = caseDefinitionRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));

        List<CaseEntry> entries = caseEntryRepository.findByCaseIdOrderBySkinIdAsc(caseId);

        Map<String, Skin> skinsById = skinRepository
                .findAllById(entries.stream().map(CaseEntry::getSkinId).toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));

        List<CaseDropResponse> drops = entries.stream()
                .map(entry -> toDropResponse(entry, skinsById.get(entry.getSkinId())))
                .filter(java.util.Objects::nonNull)
                .toList();

        return new CaseDetailResponse(
                caseDef.getId(),
                caseDef.getDisplayName(),
                caseDef.getPriceVp(),
                caseDef.getImageRef(),
                caseDef.isActive(),
                drops
        );
    }

    private static SkinResponse toSkinResponse(Skin skin) {
        return new SkinResponse(
                skin.getId(),
                skin.getDisplayName(),
                skin.getWeapon(),
                skin.getRarity(),
                skin.getVpValue(),
                skin.getImageRef(),
                skin.isActive()
        );
    }

    private static CaseSummaryResponse toCaseSummary(CaseDefinition caseDef) {
        return new CaseSummaryResponse(
                caseDef.getId(),
                caseDef.getDisplayName(),
                caseDef.getPriceVp(),
                caseDef.getImageRef(),
                caseDef.isActive()
        );
    }

    /**
     * Maps an entry to a drop response. Returns {@code null} if the referenced
     * skin row is missing (defensive — the FK should normally prevent this).
     */
    private static CaseDropResponse toDropResponse(CaseEntry entry, Skin skin) {
        if (skin == null) {
            return null;
        }
        return new CaseDropResponse(
                skin.getId(),
                entry.getWeight(),
                skin.getDisplayName(),
                skin.getWeapon(),
                skin.getRarity(),
                skin.getVpValue(),
                skin.getImageRef()
        );
    }
}
