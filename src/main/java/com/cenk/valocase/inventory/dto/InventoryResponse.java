package com.cenk.valocase.inventory.dto;

import java.util.List;

/**
 * All inventory items owned by an account, newest first.
 */
public record InventoryResponse(
        String accountId,
        int count,
        List<InventoryItemResponse> items
) {
}
