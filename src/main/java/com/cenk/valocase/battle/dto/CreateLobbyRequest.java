package com.cenk.valocase.battle.dto;

import java.util.List;

/**
 * Request to create a public battle lobby. {@code maxSlots} is the participant
 * count (2 = 1v1, 3 = 1v1v1, 4 = 1v1v1v1).
 *
 * <p>Multi-case clients send {@code caseSelections} (1..5 cases, each quantity
 * 1..5). The legacy single-case shape ({@code caseId} + {@code rounds}) is still
 * accepted and is normalized to one selection of {@code caseId} x {@code rounds}.
 * The server always computes the entry cost; any client-sent cost is ignored.
 */
public record CreateLobbyRequest(
        String caseId,
        Integer rounds,
        Integer maxSlots,
        List<CaseSelectionRequest> caseSelections
) {

    /** Effective selections: the new list when present, else the legacy single case. */
    public List<CaseSelectionRequest> normalizedSelections() {
        if (caseSelections != null && !caseSelections.isEmpty()) {
            return caseSelections;
        }
        if (caseId != null && !caseId.isBlank()) {
            return List.of(new CaseSelectionRequest(caseId, rounds));
        }
        return List.of();
    }
}
