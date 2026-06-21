package com.cenk.valocase.upgrade.dto;

/**
 * Read-only preview of an upgrade: whether it is allowed, the exact success
 * chance the real roll would use, the total input value, and the target value.
 * {@code reason} is null when allowed, otherwise an upgrade error code.
 */
public record UpgradePreviewResponse(
        boolean canUpgrade,
        double chancePercent,
        String reason,
        long inputValue,
        long targetValue
) {
}
