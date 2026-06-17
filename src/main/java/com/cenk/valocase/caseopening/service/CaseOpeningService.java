package com.cenk.valocase.caseopening.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.caseopening.domain.CaseOpening;
import com.cenk.valocase.caseopening.dto.OpenCaseResultResponse;
import com.cenk.valocase.caseopening.dto.WonSkinResponse;
import com.cenk.valocase.caseopening.repository.CaseOpeningRepository;
import com.cenk.valocase.catalog.domain.CaseDefinition;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.CaseDefinitionRepository;
import com.cenk.valocase.catalog.repository.CaseEntryRepository;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.service.InventoryService;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.event.MissionProgressEvent;
import com.cenk.valocase.progression.CategoryLockedException;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.progression.dto.CaseOpenProgressionResponse;
import com.cenk.valocase.progression.service.ProgressionService;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative case opening. The whole operation is a single
 * transaction: VP is never deducted without a skin granted and vice versa — if
 * any step fails, everything rolls back.
 */
@Service
@RequiredArgsConstructor
public class CaseOpeningService {

    /** Wallet transaction reason for a case purchase. */
    public static final String REASON_CASE_OPEN = "CASE_OPEN";

    private final CaseDefinitionRepository caseDefinitionRepository;
    private final CaseEntryRepository caseEntryRepository;
    private final SkinRepository skinRepository;
    private final WalletService walletService;
    private final InventoryService inventoryService;
    private final CaseOpeningRepository caseOpeningRepository;
    private final DropSelector dropSelector;
    private final ApplicationEventPublisher eventPublisher;
    private final AccountRepository accountRepository;
    private final ProgressionService progressionService;

    @Transactional
    public OpenCaseResultResponse open(UUID accountId, String caseId) {
        // 1-2. Load the case and require it to be active.
        CaseDefinition caseDef = caseDefinitionRepository.findById(caseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Case not found: " + caseId));
        if (!caseDef.isActive()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Case is not available: " + caseId);
        }

        // Load the player and enforce category unlock BEFORE any reward roll,
        // VP charge or XP grant. A locked category never mutates state.
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Account not found: " + accountId));
        CaseCategory.fromCaseId(caseId).ifPresent(category -> {
            if (!progressionService.isCategoryUnlocked(account.getLevel(), category)) {
                throw new CategoryLockedException(category, account.getLevel());
            }
        });

        // 3-4. Load the drop pool; reject an empty configuration.
        List<CaseEntry> entries = caseEntryRepository.findByCaseIdOrderBySkinIdAsc(caseId);
        if (entries.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Case has no drop entries: " + caseId);
        }

        // Resolve skins and keep only entries pointing at a present, active skin.
        Map<String, Skin> skinsById = skinRepository
                .findAllById(entries.stream().map(CaseEntry::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));

        List<CaseEntry> candidates = entries.stream()
                .filter(entry -> {
                    Skin skin = skinsById.get(entry.getSkinId());
                    return skin != null && skin.isActive();
                })
                .toList();

        if (candidates.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Case has no valid (active) drop entries: " + caseId);
        }

        // 6. Weighted random selection from the eligible pool.
        CaseEntry winningEntry = dropSelector.selectWeighted(candidates);
        Skin wonSkin = skinsById.get(winningEntry.getSkinId());
        if (wonSkin == null || !wonSkin.isActive()) {
            // Defensive: selection should only return eligible entries.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Selected skin is invalid: " + winningEntry.getSkinId());
        }

        // Record the opening first so we have a stable id to thread through.
        CaseOpening opening = new CaseOpening();
        opening.setAccountId(accountId);
        opening.setCaseId(caseId);
        opening.setWonSkinId(wonSkin.getId());
        opening.setPricePaid(caseDef.getPriceVp());
        opening.setCreatedAt(Instant.now());
        opening = caseOpeningRepository.save(opening);
        UUID openingId = opening.getId();

        // 5. Debit the case price (skipped only for a free case).
        long newVpBalance;
        if (caseDef.getPriceVp() > 0) {
            Wallet wallet = walletService.debit(accountId, caseDef.getPriceVp(), REASON_CASE_OPEN, openingId);
            newVpBalance = wallet.getVpBalance();
        } else {
            newVpBalance = walletService.getWalletForAccount(accountId).vpBalance();
        }

        // 7. Grant the won skin to inventory, linked to this opening.
        InventoryItem item = inventoryService.addItem(
                accountId, wonSkin.getId(), InventoryService.SOURCE_CASE_OPENING, openingId);
        opening.setInventoryItemId(item.getId());

        // Mission progress: a successful case opening, within this transaction.
        eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.CASE_OPENED, 1));

        // Grant case-open XP only now that the reward is committed. The account
        // is managed by this transaction, so changes flush on commit and roll
        // back with everything else on any failure.
        CaseOpenProgressionResponse progression =
                progressionService.grantCaseOpenXp(account, ProgressionService.XP_PER_CASE_OPEN);

        // 8. Build the result.
        WonSkinResponse wonSkinResponse = new WonSkinResponse(
                wonSkin.getId(),
                wonSkin.getDisplayName(),
                wonSkin.getWeapon(),
                wonSkin.getRarity(),
                wonSkin.getVpValue(),
                wonSkin.getImageRef()
        );

        return new OpenCaseResultResponse(
                openingId.toString(),
                caseId,
                wonSkinResponse,
                newVpBalance,
                item.getId().toString(),
                progression
        );
    }
}
