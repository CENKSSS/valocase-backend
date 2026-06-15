package com.cenk.valocase.battle.dto;

/**
 * Request to create and resolve a bot battle. participantCount includes the
 * user (index 0); the rest are bots.
 */
public record BattleRequest(
        String caseId,
        int rounds,
        int participantCount
) {
}
