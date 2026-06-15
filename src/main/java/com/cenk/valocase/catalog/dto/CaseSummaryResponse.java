package com.cenk.valocase.catalog.dto;

/**
 * Case representation without drop-pool details (used by the list endpoint).
 */
public record CaseSummaryResponse(
        String id,
        String displayName,
        int priceVp,
        String imageRef,
        boolean active
) {
}
