package com.cenk.valocase.catalog.dto;

import java.util.List;

/**
 * Full case representation. {@code drops} contains only entries the backend can
 * actually roll (active skin, positive weight), matching case-opening eligibility.
 * {@code expectedValueVp} is the weighted-average VP of those drops. The
 * player-aware fields are null when the request is anonymous.
 */
public record CaseDetailResponse(
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
        Boolean affordable,
        long expectedValueVp,
        List<CaseDropResponse> drops
) {
}
