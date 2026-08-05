package com.cenk.valocase.account.dto;

/**
 * Returned when a guest account is created. The {@code guestToken} must be
 * stored by the client and sent back in the {@code X-Guest-Token} header.
 *
 * <p>{@code countryCode} is the stored uppercase ISO-3166-1 alpha-2 code, or
 * null when the account was created without one during the migration window. It
 * is never a localized country name: turning "TR" into "Türkiye" is the client's
 * job, in the player's own language.
 */
public record GuestRegisterResponse(
        String accountId,
        String guestToken,
        String displayName,
        String avatarId,
        String countryCode,
        String status,
        long vpBalance,
        long diamondBalance
) {
}
