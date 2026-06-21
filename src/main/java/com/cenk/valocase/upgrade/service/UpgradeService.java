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
import com.cenk.valocase.upgrade.domain.UpgradeTarget;
import com.cenk.valocase.upgrade.dto.UpgradeResultResponse;
import com.cenk.valocase.upgrade.repository.UpgradeInputRepository;
import com.cenk.valocase.upgrade.repository.UpgradeRepository;
import com.cenk.valocase.upgrade.repository.UpgradeTargetRepository;

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

    /** Error code Unity maps to the "Yükseltilemez" message. */
    public static final String CODE_UPGRADE_NOT_POSSIBLE = "UPGRADE_NOT_POSSIBLE";

    private final InventoryItemRepository inventoryItemRepository;
    private final SkinRepository skinRepository;
    private final UpgradeRepository upgradeRepository;
    private final UpgradeInputRepository upgradeInputRepository;
    private final UpgradeTargetRepository upgradeTargetRepository;
    private final InventoryService inventoryService;
    private final UpgradeChanceCalculator chanceCalculator;
    private final ApplicationEventPublisher eventPublisher;

    /** Backward-compatible single-target entry point. */
    @Transactional
    public UpgradeResultResponse upgrade(UUID accountId, List<String> rawInputItemIds, String targetSkinId) {
        if (targetSkinId == null || targetSkinId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds must not be empty");
        }
        return upgrade(accountId, rawInputItemIds, List.of(targetSkinId));
    }

    @Transactional
    public UpgradeResultResponse upgrade(UUID accountId, List<String> rawInputItemIds, List<String> rawTargetSkinIds) {
        // 1. Basic request validation.
        if (rawInputItemIds == null || rawInputItemIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "inputItemIds must not be empty");
        }
        if (rawTargetSkinIds == null || rawTargetSkinIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds must not be empty");
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

        // 3. Reject duplicate input items.
        Set<UUID> distinctIds = new LinkedHashSet<>(inputItemIds);
        if (distinctIds.size() != inputItemIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate input item IDs are not allowed");
        }

        // 4. Normalize target ids, reject blanks and duplicates.
        List<String> targetSkinIds = new ArrayList<>(rawTargetSkinIds.size());
        Set<String> distinctTargets = new LinkedHashSet<>();
        for (String raw : rawTargetSkinIds) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds contains a blank id");
            }
            String id = raw.trim();
            if (!distinctTargets.add(id)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Duplicate target skin IDs are not allowed");
            }
            targetSkinIds.add(id);
        }

        // 5. Every target skin must exist and be active.
        Map<String, Skin> targetsById = skinRepository.findAllById(distinctTargets).stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));
        for (String id : targetSkinIds) {
            Skin target = targetsById.get(id);
            if (target == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Target skin not found: " + id);
            }
            if (!target.isActive()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Target skin is not available: " + id);
            }
        }

        // 6. Lock and load the input items (must all be owned and still present).
        List<InventoryItem> items = inventoryItemRepository.findForUpdateByIdInAndAccountId(distinctIds, accountId);
        if (items.size() != distinctIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "One or more input items are not owned or no longer available");
        }

        // 7. Compute total input value from catalog vpValues.
        Map<String, Skin> skinsById = skinRepository
                .findAllById(items.stream().map(InventoryItem::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));
        long inputValue = items.stream().mapToLong(item -> skinValue(skinsById, item.getSkinId())).sum();
        long targetValue = targetSkinIds.stream().mapToLong(id -> targetsById.get(id).getVpValue()).sum();

        // 8. Input value must not exceed target value (no downgrade); blocks cleanly.
        if (inputValue > targetValue) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Total input value (" + inputValue + ") must not exceed target value (" + targetValue + ")",
                    CODE_UPGRADE_NOT_POSSIBLE);
        }

        // 9. Compute chance and roll, server-side.
        boolean targetIsMelee = targetSkinIds.stream().anyMatch(id -> isMelee(targetsById.get(id)));
        int meleeInputCount = (int) items.stream().filter(item -> isMelee(skinsById.get(item.getSkinId()))).count();
        double chance = chanceCalculator.computeChance(inputValue, targetValue, targetIsMelee, meleeInputCount);
        boolean success = chanceCalculator.roll(chance);

        // 10. Record the attempt first (gives a stable upgradeId).
        Upgrade upgrade = new Upgrade();
        upgrade.setAccountId(accountId);
        upgrade.setTargetSkinId(targetSkinIds.get(0));
        upgrade.setInputCount(items.size());
        upgrade.setInputValue(inputValue);
        upgrade.setTargetValue(targetValue);
        upgrade.setChance(chance);
        upgrade.setSuccess(success);
        upgrade.setCreatedAt(Instant.now());
        upgrade = upgradeRepository.saveAndFlush(upgrade);
        UUID upgradeId = upgrade.getId();

        // 11. Snapshot the consumed inputs.
        List<UpgradeInput> inputSnapshots = items.stream().map(item -> {
            UpgradeInput input = new UpgradeInput();
            input.setUpgradeId(upgradeId);
            input.setItemId(item.getId());
            input.setSkinId(item.getSkinId());
            input.setVpValue((int) skinValue(skinsById, item.getSkinId()));
            return input;
        }).toList();
        upgradeInputRepository.saveAll(inputSnapshots);

        // 12. Always consume the input items.
        List<String> consumedItemIds = items.stream().map(item -> item.getId().toString()).toList();
        inventoryItemRepository.deleteAll(items);

        // 13. Grant every target only on success; snapshot the targets either way.
        List<String> grantedItemIds = new ArrayList<>();
        List<UpgradeTarget> targetSnapshots = new ArrayList<>(targetSkinIds.size());
        for (String id : targetSkinIds) {
            UpgradeTarget snapshot = new UpgradeTarget();
            snapshot.setUpgradeId(upgradeId);
            snapshot.setSkinId(id);
            snapshot.setVpValue(targetsById.get(id).getVpValue());
            if (success) {
                InventoryItem granted = inventoryService.addItem(accountId, id, INVENTORY_SOURCE_UPGRADE, null);
                snapshot.setGrantedInventoryItemId(granted.getId());
                grantedItemIds.add(granted.getId().toString());
            }
            targetSnapshots.add(snapshot);
        }
        upgradeTargetRepository.saveAll(targetSnapshots);

        if (success) {
            upgrade.setGrantedInventoryItemId(targetSnapshots.get(0).getGrantedInventoryItemId());
            eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.UPGRADE_SUCCEEDED, 1));
        }

        // 14. Authoritative result.
        return new UpgradeResultResponse(
                upgradeId.toString(),
                success,
                chance,
                consumedItemIds,
                targetSkinIds.get(0),
                targetSkinIds,
                grantedItemIds.isEmpty() ? null : grantedItemIds.get(0),
                grantedItemIds
        );
    }

    private static long skinValue(Map<String, Skin> skinsById, String skinId) {
        Skin skin = skinsById.get(skinId);
        return skin != null ? skin.getVpValue() : 0;
    }

    private static boolean isMelee(Skin skin) {
        return skin != null && "Melee".equalsIgnoreCase(skin.getWeapon());
    }
}
