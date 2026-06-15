package com.cenk.valocase.inventory.dto;

/**
 * Request to sell a single owned instance of the given skin.
 */
public record SellOneRequest(
        String skinId
) {
}
