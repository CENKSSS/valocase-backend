package com.cenk.valocase.battle.dto;

/**
 * Request to create a public battle lobby. {@code maxSlots} is the participant
 * count (2 = 1v1, 3 = 1v1v1, 4 = 1v1v1v1) using the existing battle slot range;
 * {@code rounds} is the existing battle amount / round count.
 */
public record CreateLobbyRequest(
        String caseId,
        int rounds,
        int maxSlots
) {
}
