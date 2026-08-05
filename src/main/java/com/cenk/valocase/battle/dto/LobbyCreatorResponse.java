package com.cenk.valocase.battle.dto;

/**
 * Identity of the lobby creator. {@code countryCode} is the creator's
 * self-selected ISO-3166-1 alpha-2 code (uppercase), or null when never picked.
 */
public record LobbyCreatorResponse(
        String accountId,
        String displayName,
        String avatarId,
        String countryCode
) {
}
