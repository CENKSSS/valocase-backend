package com.cenk.valocase.battle.dto;

/**
 * One rolled skin, with display fields flattened in so Unity needs no extra
 * catalog lookup.
 */
public record RolledSkinResponse(
        String skinId,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef
) {
}
