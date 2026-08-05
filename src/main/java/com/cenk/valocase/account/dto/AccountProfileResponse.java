package com.cenk.valocase.account.dto;

/**
 * The account's own profile fields, returned after a rename or a country
 * change so the client never has to guess what was stored.
 *
 * <p>{@code countryCode} is the uppercase ISO code or null; the display name for
 * it is resolved client-side, in the player's language.
 */
public record AccountProfileResponse(
        String accountId,
        String displayName,
        String countryCode
) {
}
