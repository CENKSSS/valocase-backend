package com.cenk.valocase.caseopening.dto;

/**
 * The skin won from a case opening.
 */
public record WonSkinResponse(
        String skinId,
        String displayName,
        String weapon,
        String rarity,
        int vpValue,
        String imageRef
) {
}
