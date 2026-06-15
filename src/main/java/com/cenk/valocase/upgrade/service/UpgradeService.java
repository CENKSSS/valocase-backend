package com.cenk.valocase.upgrade.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.inventory.service.InventoryService;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.event.MissionProgressEvent;
import com.cenk.valocase.upgrade.domain.Upgrade;
import com.cenk.valocase.upgrade.domain.UpgradeInput;
import com.cenk.valocase.upgrade.dto.UpgradeResultResponse;
import com.cenk.valocase.upgrade.repository.UpgradeInputRepository;
import com.cenk.valocase.upgrade.repository.UpgradeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative skin upgrade. The whole operation is one transaction:
 * input items are always consumed and the attempt is always recorded; the target
 * is granted only on a successful roll. Any failure rolls everything back.
 */
@Service
@RequiredArgsConstructor
public class UpgradeService {

    /** Source recorded on an inventory item granted by a successful upgrade. */
    public static final String INVENTORY_SOURCE_UPGRADE = "UPGRADE";

    private final InventoryItemRepository inventoryItemRepository;
    private final SkinRepository skinRepository;
    private final UpgradeRepository upgradeRepository;
    private final UpgradeInputRepository upgradeInputRepository;
    private final InventoryService inventoryService;
    private final UpgradeChanceCalculator chanceCalculator;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public UpgradeResultResponse upgrade(UUID accountId, List<String> rawInputItemIds, String targetSkinId) {
        // 1. Basic request validation.
        if (rawInputItemIds == null || rawInputItemIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "inputItemIds must not be empty");
        }
        if (targetSkinId == null || targetSkinId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinId is required");
        }

        // 2. Parse the item ids.
        List<UUID> inputItemIds = new ArrayList<>(rawInputItemIds.size());
        for (String raw : rawInputItemIds) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "inputItemIds contains a blank id");
            }
            try {
                inputItemIds.add(UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid item id: " + raw);
            }
        }

        // 3. Reject duplicates.
        Set<UUID> distinctIds = new LinkedHashSet<>(inputItemIds);
        if (distinctIds.size() != inputItemIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate input item IDs are not allowed");
        }

        // 4. Target skin must exist and be active.
        Skin targetSkin = skinRepository.findById(targetSkinId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Target skin not found: " + targetSkinId));
        if (!targetSkin.isActive()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Target skin is not available: " + targetSkinId);
        }

        // 5. Lock and load the input items (must all be owned and still present).
        List<InventoryItem> items = inventoryItemRepository.findForUpdateByIdInAndAccountId(distinctIds, accountId);
        if (items.size() != distinctIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "One or more input items are not owned or no longer available");
        }

        // 6. Compute input value from catalog vpValues.
        Map<String, Skin> skinsById = skinRepository
                .findAllById(items.stream().map(InventoryItem::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));
        long inputValue = items.stream().mapToLong(item -> skinValue(skinsById, item.getSkinId())).sum();
        long targetValue = targetSkin.getVpValue();

        // 7. Target must be worth strictly more than the inputs.
        if (targetValue <= inputValue) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Target value (" + targetValue + ") must be greater than total input value (" + inputValue + ")");
        }

        // 8. Compute chance and roll, server-side.
        double chance = chanceCalculator.computeChance(inputValue, targetValue);
        boolean success = chanceCalculator.roll(chance);

        // 9. Record the attempt first (gives a stable upgradeId).
        Upgrade upgrade = new Upgrade();
        upgrade.setAccountId(accountId);
        upgrade.setTargetSkinId(targetSkinId);
        upgrade.setInputCount(items.size());
        upgrade.setInputValue(inputValue);
        upgrade.setTargetValue(targetValue);
        upgrade.setChance(chance);
        upgrade.setSuccess(success);
        upgrade.setCreatedAt(Instant.now());
        upgrade = upgradeRepository.saveAndFlush(upgrade);
        UUID upgradeId = upgrade.getId();

        // 10. Snapshot the consumed inputs.
        List<UpgradeInput> snapshots = items.stream().map(item -> {
            UpgradeInput input = new UpgradeInput();
            input.setUpgradeId(upgradeId);
            input.setItemId(item.getId());
            input.setSkinId(item.getSkinId());
            input.setVpValue((int) skinValue(skinsById, item.getSkinId()));
            return input;
        }).toList();
        upgradeInputRepository.saveAll(snapshots);

        // 11. Always consume the input items.
        List<String> consumedItemIds = items.stream().map(item -> item.getId().toString()).toList();
        inventoryItemRepository.deleteAll(items);

        // 12. Grant the target only on success.
        UUID grantedItemId = null;
        if (success) {
            InventoryItem granted = inventoryService.addItem(accountId, targetSkinId, INVENTORY_SOURCE_UPGRADE, null);
            grantedItemId = granted.getId();
            upgrade.setGrantedInventoryItemId(grantedItemId);
            eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.UPGRADE_SUCCEEDED, 1));
        }

        // 13. Authoritative result.
        return new UpgradeResultResponse(
                upgradeId.toString(),
                success,
                chance,
                consumedItemIds,
                targetSkinId,
                grantedItemId != null ? grantedItemId.toString() : null
        );
    }

    private static long skinValue(Map<String, Skin> skinsById, String skinId) {
        Skin skin = skinsById.get(skinId);
        return skin != null ? skin.getVpValue() : 0;
    }
}
