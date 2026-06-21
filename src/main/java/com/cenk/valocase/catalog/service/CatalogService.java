package com.cenk.valocase.catalog.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.domain.Account;
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
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Read-only catalog access. Case detail/drop preview uses the same eligibility
 * filter as case opening (active skin, positive weight) so the client never
 * shows a drop the backend cannot roll. Endpoints are optionally player-aware:
 * when a viewer is supplied, unlock/affordability state is computed server-side.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService {

    static final String LOCKED_INACTIVE = "CASE_INACTIVE";
    static final String LOCKED_LEVEL = "LEVEL_LOCKED";

    private final SkinRepository skinRepository;
    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseEntryRepository caseEntryRepository;
    private final ProgressionService progressionService;
    private final WalletService walletService;

    public List<SkinResponse> getActiveSkins() {
        return skinRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(CatalogService::toSkinResponse)
                .toList();
    }

    public List<CaseSummaryResponse> getActiveCases(Account viewer) {
        Long balance = viewerBalance(viewer);
        return caseDefinitionRepository.findByActiveTrueOrderByDisplayNameAsc().stream()
                .map(caseDef -> toCaseSummary(caseDef, playerView(caseDef, viewer, balance)))
                .toList();
    }

    public CaseDetailResponse getCaseDetail(String caseId, Account viewer) {
        CaseDefinition caseDef = caseDefinitionRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));

        List<CaseEntry> entries = caseEntryRepository.findByCaseIdOrderBySkinIdAsc(caseId);
        Map<String, Skin> skinsById = skinRepository
                .findAllById(entries.stream().map(CaseEntry::getSkinId).toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));

        List<CaseEntry> eligible = entries.stream()
                .filter(entry -> rollable(skinsById.get(entry.getSkinId()), entry))
                .toList();
        long totalWeight = eligible.stream().mapToLong(CaseEntry::getWeight).sum();

        List<CaseDropResponse> drops = eligible.stream()
                .map(entry -> toDropResponse(entry, skinsById.get(entry.getSkinId()), totalWeight))
                .toList();
        long expectedValueVp = (long) Math.floor(drops.stream()
                .mapToDouble(drop -> drop.dropChance() * drop.vpValue())
                .sum());

        PlayerView view = playerView(caseDef, viewer, viewerBalance(viewer));
        return new CaseDetailResponse(
                caseDef.getId(), caseDef.getDisplayName(), caseDef.getPriceVp(), caseDef.getImageRef(),
                caseDef.isActive(), view.weaponCategory(), view.requiredLevel(),
                view.canOpen(), view.lockedReason(), view.currentLevel(), view.affordable(),
                expectedValueVp, drops);
    }

    private static boolean rollable(Skin skin, CaseEntry entry) {
        return skin != null && skin.isActive() && entry.getWeight() > 0;
    }

    private Long viewerBalance(Account viewer) {
        return viewer == null ? null : walletService.getWalletForAccount(viewer.getId()).vpBalance();
    }

    private PlayerView playerView(CaseDefinition caseDef, Account viewer, Long balance) {
        Optional<CaseCategory> category = CaseCategory.fromCaseId(caseDef.getId());
        String weaponCategory = category.map(Enum::name).orElse(null);
        Integer requiredLevel = category.map(CaseCategory::getUnlockLevel).orElse(null);
        if (viewer == null) {
            return new PlayerView(weaponCategory, requiredLevel, null, null, null, null);
        }

        boolean unlocked = category
                .map(c -> progressionService.isCategoryUnlocked(viewer.getLevel(), c))
                .orElse(true);
        boolean canOpen;
        String lockedReason;
        if (!caseDef.isActive()) {
            canOpen = false;
            lockedReason = LOCKED_INACTIVE;
        } else if (!unlocked) {
            canOpen = false;
            lockedReason = LOCKED_LEVEL;
        } else {
            canOpen = true;
            lockedReason = null;
        }
        boolean affordable = balance != null && balance >= caseDef.getPriceVp();
        return new PlayerView(weaponCategory, requiredLevel, canOpen, lockedReason, viewer.getLevel(), affordable);
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

    private static CaseSummaryResponse toCaseSummary(CaseDefinition caseDef, PlayerView view) {
        return new CaseSummaryResponse(
                caseDef.getId(), caseDef.getDisplayName(), caseDef.getPriceVp(), caseDef.getImageRef(),
                caseDef.isActive(), view.weaponCategory(), view.requiredLevel(),
                view.canOpen(), view.lockedReason(), view.currentLevel(), view.affordable());
    }

    private static CaseDropResponse toDropResponse(CaseEntry entry, Skin skin, long totalWeight) {
        double dropChance = totalWeight > 0
                ? Math.round((double) entry.getWeight() / totalWeight * 1_000_000.0) / 1_000_000.0
                : 0.0;
        return new CaseDropResponse(
                skin.getId(),
                entry.getWeight(),
                dropChance,
                skin.getDisplayName(),
                skin.getWeapon(),
                skin.getRarity(),
                skin.getVpValue(),
                skin.getImageRef()
        );
    }

    private record PlayerView(
            String weaponCategory,
            Integer requiredLevel,
            Boolean canOpen,
            String lockedReason,
            Integer currentLevel,
            Boolean affordable) {
    }
}
