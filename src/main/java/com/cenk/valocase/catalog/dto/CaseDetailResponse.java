package com.cenk.valocase.catalog.dto;

import java.util.List;

/**
 * Full case representation including its drop-pool entries.
 */
public record CaseDetailResponse(
        String id,
        String displayName,
        int priceVp,
        String imageRef,
        boolean active,
        List<CaseDropResponse> drops
) {
}
