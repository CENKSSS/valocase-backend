package com.cenk.valocase.battle.dto;

import java.util.List;

/**
 * One participant's result: index, identity, total VP, and the skins rolled in
 * each round (ordered by round).
 */
public record BattleParticipantResponse(
        int index,
        boolean isUser,
        String name,
        String avatarId,
        long totalVp,
        List<RolledSkinResponse> rounds
) {
}
