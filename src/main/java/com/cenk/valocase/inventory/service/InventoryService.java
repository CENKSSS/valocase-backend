package com.cenk.valocase.inventory.service;

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

import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.dto.InventoryItemResponse;
import com.cenk.valocase.inventory.dto.InventoryResponse;
import com.cenk.valocase.inventory.dto.SellOneResponse;
import com.cenk.valocase.inventory.dto.SellResultResponse;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.event.MissionProgressEvent;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Per-instance inventory reads, the case-opening write path ({@link #addItem}),
 * and selling items back for VP. Selling reuses {@link WalletService#credit} so
 * the wallet stays the single owner of balance mutations.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    /** Source recorded for items awarded by opening a case. */
    public static final String SOURCE_CASE_OPENING = "CASE_OPENING";

    /** Wallet transaction reason recorded when selling inventory back for VP. */
    public static final String REASON_INVENTORY_SELL = "INVENTORY_SELL";

    private final InventoryItemRepository inventoryItemRepository;
    private final SkinRepository skinRepository;
    private final WalletService walletService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public InventoryResponse getInventory(UUID accountId) {
        List<InventoryItem> items = inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(accountId);

        Map<String, Skin> skinsById = skinRepository
                .findAllById(items.stream().map(InventoryItem::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));

        List<InventoryItemResponse> responses = items.stream()
                .map(item -> toResponse(item, skinsById.get(item.getSkinId())))
                .toList();

        return new InventoryResponse(accountId.toString(), responses.size(), responses);
    }

    /**
     * Adds one owned skin instance to an account's inventory. Internal API for
     * case opening — not reachable from any endpoint in Phase 1.
     */
    @Transactional
    public InventoryItem addItem(UUID accountId, String skinId, String source, UUID caseOpeningId) {
        InventoryItem item = new InventoryItem();
        item.setAccountId(accountId);
        item.setSkinId(skinId);
        item.setSource(source);
        item.setCaseOpeningId(caseOpeningId);
        item.setAcquiredAt(Instant.now());
        return inventoryItemRepository.save(item);
    }

    /**
     * Sells one owned instance of a skin: removes the oldest instance and credits
     * its catalog vpValue to the wallet. 404 if the account owns none.
     */
    @Transactional
    public SellOneResponse sellOne(UUID accountId, String skinId) {
        if (skinId == null || skinId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "skinId is required");
        }

        InventoryItem item = inventoryItemRepository
                .findFirstByAccountIdAndSkinIdOrderByAcquiredAtAsc(accountId, skinId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Inventory item not owned: " + skinId));

        long vpGained = skinValue(skinId);
        UUID referenceId = item.getId();
        inventoryItemRepository.delete(item);

        long newBalance = creditAndGetBalance(accountId, vpGained, referenceId);

        eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.SKIN_SOLD, 1));
        return new SellOneResponse(skinId, vpGained, newBalance);
    }

    /**
     * Sells every owned item, crediting the total catalog vpValue. 422 if the
     * inventory is empty.
     */
    @Transactional
    public SellResultResponse sellAll(UUID accountId) {
        List<InventoryItem> items = inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(accountId);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Nothing eligible to sell");
        }
        return sellItems(accountId, items);
    }

    /**
     * Sells every owned item whose catalog vpValue is at most maxVpValue. 422 if
     * nothing matches.
     */
    @Transactional
    public SellResultResponse sellBelowValue(UUID accountId, int maxVpValue) {
        if (maxVpValue < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "maxVpValue must be >= 0");
        }

        List<InventoryItem> items = inventoryItemRepository.findByAccountIdOrderByAcquiredAtDesc(accountId);
        Map<String, Skin> skinsById = loadSkins(items);

        List<InventoryItem> eligible = items.stream()
                .filter(item -> valueOf(skinsById.get(item.getSkinId())) <= maxVpValue)
                .toList();
        if (eligible.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Nothing eligible to sell");
        }
        return sellItems(accountId, eligible, skinsById);
    }

    private SellResultResponse sellItems(UUID accountId, List<InventoryItem> items) {
        return sellItems(accountId, items, loadSkins(items));
    }

    private SellResultResponse sellItems(UUID accountId, List<InventoryItem> items, Map<String, Skin> skinsById) {
        long total = items.stream().mapToLong(item -> valueOf(skinsById.get(item.getSkinId()))).sum();
        inventoryItemRepository.deleteAll(items);
        long newBalance = creditAndGetBalance(accountId, total, null);

        eventPublisher.publishEvent(new MissionProgressEvent(accountId, MissionEventTypes.SKIN_SOLD, items.size()));
        return new SellResultResponse(items.size(), total, newBalance);
    }

    /** Credits VP (reusing WalletService) and returns the resulting balance. */
    private long creditAndGetBalance(UUID accountId, long amount, UUID referenceId) {
        if (amount > 0) {
            return walletService.credit(accountId, amount, REASON_INVENTORY_SELL, referenceId).getVpBalance();
        }
        // Nothing to credit (e.g. zero-value skins); just read the current balance.
        return walletService.getWalletForAccount(accountId).vpBalance();
    }

    private long skinValue(String skinId) {
        return skinRepository.findById(skinId).map(Skin::getVpValue).orElse(0);
    }

    private static long valueOf(Skin skin) {
        return skin != null ? skin.getVpValue() : 0;
    }

    private Map<String, Skin> loadSkins(List<InventoryItem> items) {
        return skinRepository
                .findAllById(items.stream().map(InventoryItem::getSkinId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Skin::getId, Function.identity()));
    }

    /**
     * Maps an item to a response. Skin display fields fall back to null/0 if the
     * referenced skin row is missing (defensive — the FK should prevent this).
     */
    private static InventoryItemResponse toResponse(InventoryItem item, Skin skin) {
        return new InventoryItemResponse(
                item.getId().toString(),
                item.getSkinId(),
                skin != null ? skin.getDisplayName() : null,
                skin != null ? skin.getWeapon() : null,
                skin != null ? skin.getRarity() : null,
                skin != null ? skin.getVpValue() : 0,
                skin != null ? skin.getImageRef() : null,
                item.getSource(),
                item.getAcquiredAt()
        );
    }
}
