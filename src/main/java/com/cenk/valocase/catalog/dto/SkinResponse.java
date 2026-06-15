package com.cenk.valocase.catalog.dto;

/**
 * Public representation of a {@code Skin}.
 */
public record SkinResponse(
        String id,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef,
        boolean active
) {
}
