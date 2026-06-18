package com.cenk.valocase.battle.dto;

import java.util.List;

/**
 * One lobby slot for Unity. {@code type} is EMPTY, REAL or BOT. {@code totalVp}
 * and {@code rounds} are populated only once the battle is COMPLETED.
 * {@code addBotAllowed} is true only for an EMPTY slot once the 3-second
 * server-side delay has elapsed and the lobby is still WAITING.
 */
public record LobbySlotResponse(
        int slotIndex,
        String type,
        String accountId,
        String displayName,
        boolean creator,
        boolean addBotAllowed,
        Long totalVp,
        List<RolledSkinResponse> rounds
) {
}
