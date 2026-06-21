package com.cenk.valocase.catalog.dto;

/**
 * Case representation without drop-pool details (used by the list endpoint).
 * The player-aware fields ({@code canOpen}, {@code lockedReason},
 * {@code currentLevel}, {@code affordable}) are null when the request is
 * anonymous.
 */
public record CaseSummaryResponse(
        String id,
        String displayName,
        int priceVp,
        String imageRef,
        boolean active,
        String weaponCategory,
        Integer requiredLevel,
        Boolean canOpen,
        String lockedReason,
        Integer currentLevel,
        Boolean affordable
) {
}
