package com.cenk.valocase.catalog.dto;

/**
 * One drop-pool entry within a case detail: the skin plus its relative weight.
 * Skin display fields are flattened in for convenience on the client.
 */
public record CaseDropResponse(
        String skinId,
        int weight,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef
) {
}
