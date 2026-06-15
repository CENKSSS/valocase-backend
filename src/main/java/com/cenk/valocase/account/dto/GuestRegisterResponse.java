package com.cenk.valocase.account.dto;

/**
 * Returned when a guest account is created. The {@code guestToken} must be
 * stored by the client and sent back in the {@code X-Guest-Token} header.
 */
public record GuestRegisterResponse(
        String accountId,
        String guestToken,
        String displayName,
        String status,
        long vpBalance
) {
}
