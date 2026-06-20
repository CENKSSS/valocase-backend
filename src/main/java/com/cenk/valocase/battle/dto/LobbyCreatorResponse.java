package com.cenk.valocase.battle.dto;

/** Identity of the lobby creator. */
public record LobbyCreatorResponse(
        String accountId,
        String displayName,
        String avatarId
) {
}
