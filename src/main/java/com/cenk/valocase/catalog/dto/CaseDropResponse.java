package com.cenk.valocase.catalog.dto;

/**
 * One drop-pool entry within a case detail: the skin plus its relative weight and
 * backend-computed drop chance (fraction in [0,1] over the eligible pool). Only
 * skins the backend can actually roll are emitted. Skin display fields are
 * flattened in for convenience on the client.
 */
public record CaseDropResponse(
        String skinId,
        int weight,
        double dropChance,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef
) {
}
