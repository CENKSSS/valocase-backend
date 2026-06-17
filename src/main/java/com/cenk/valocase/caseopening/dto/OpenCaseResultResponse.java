package com.cenk.valocase.caseopening.dto;

import com.cenk.valocase.progression.dto.CaseOpenProgressionResponse;

/**
 * Result of a server-authoritative case opening.
 */
public record OpenCaseResultResponse(
        String openingId,
        String caseId,
        WonSkinResponse wonSkin,
        long newVpBalance,
        String inventoryItemId,
        CaseOpenProgressionResponse progression
) {
}
