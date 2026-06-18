package com.cenk.valocase.battle.dto;

import java.time.Instant;
import java.util.List;

/**
 * Full lobby / waiting-room view for Unity. The same shape is used for the list
 * endpoint and the single-lobby status endpoint, and carries the resolved result
 * (winner + per-slot rolls) once the lobby is COMPLETED.
 *
 * <p>{@code battleId} is the lobby id (also the id used for join / add-bot /
 * cancel and for fetching status). {@code addBotAvailable} reflects the shared,
 * server-side 3-second delay; {@code addBotAvailableAt} is the exact instant
 * after which Add Bot becomes allowed so every client agrees.
 */
public record LobbyResponse(
        String battleId,
        String status,
        LobbyCreatorResponse creator,
        String caseId,
        String caseName,
        int rounds,
        long entryCost,
        int maxSlots,
        int filledSlots,
        List<LobbySlotResponse> slots,
        Instant createdAt,
        Instant addBotAvailableAt,
        boolean addBotAvailable,
        Instant readyAt,
        Integer winnerSlotIndex,
        String winnerDisplayName
) {
}
