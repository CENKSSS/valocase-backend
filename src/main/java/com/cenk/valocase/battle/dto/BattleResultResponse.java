package com.cenk.valocase.battle.dto;

import java.util.List;

/**
 * Full authoritative result of a bot battle. grantedInventoryItemIds is empty
 * unless the user won, in which case it lists every granted inventory item.
 *
 * <p>{@code isDraw} is true when every participant ended on the same total VP:
 * there is no winner, so {@code winnerIndex} is {@code -1}, {@code userWon} is
 * false and {@code grantedInventoryItemIds} is empty. {@code refundVp} is the
 * entry cost returned to the user in that case (0 otherwise), and
 * {@code newVpBalance} is already the post-refund balance.
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
        List<BattleParticipantResponse> participants,
        boolean isDraw,
        long refundVp
) {
}
