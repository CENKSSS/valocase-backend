package com.cenk.valocase.caseopening.dto;

/**
 * Result of a server-authoritative case opening.
 */
public record OpenCaseResultResponse(
        String openingId,
        String caseId,
        WonSkinResponse wonSkin,
        long newVpBalance,
        String inventoryItemId
) {
}
