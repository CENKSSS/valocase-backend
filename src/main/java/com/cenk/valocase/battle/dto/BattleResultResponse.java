package com.cenk.valocase.battle.dto;

import java.util.List;

/**
 * Full authoritative result of a bot battle. grantedInventoryItemIds is empty
 * unless the user won, in which case it lists every granted inventory item.
 */
public record BattleResultResponse(
        String battleId,
        String caseId,
        int rounds,
        long entryCost,
        long newVpBalance,
        int winnerIndex,
        boolean userWon,
        List<String> grantedInventoryItemIds,
        List<BattleParticipantResponse> participants
) {
}
