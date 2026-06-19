package com.cenk.valocase.battle.dto;

/**
 * One selected case in a lobby view: case id, display name, opening quantity and
 * the per-open VP price. The lobby entry cost is the sum of {@code priceVp x
 * quantity} across all selections.
 */
public record CaseSelectionResponse(String caseId, String caseName, int quantity, long priceVp) {
}
