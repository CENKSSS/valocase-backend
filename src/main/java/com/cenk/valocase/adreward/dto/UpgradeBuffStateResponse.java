package com.cenk.valocase.adreward.dto;

import java.time.Instant;

public record UpgradeBuffStateResponse(
        boolean active,
        double bonusPercent,
        Instant claimedAt
) {

    public static UpgradeBuffStateResponse inactive() {
        return new UpgradeBuffStateResponse(false, 0.0, null);
    }
}
