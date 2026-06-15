package com.cenk.valocase.inventory.dto;

/**
 * Request to sell every owned item whose catalog vpValue is at most maxVpValue.
 */
public record SellBelowValueRequest(
        int maxVpValue
) {
}
