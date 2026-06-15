package com.cenk.valocase.wallet.dto;

import java.time.Instant;

/**
 * Current wallet state for the authenticated guest.
 */
public record WalletResponse(
        String accountId,
        long vpBalance,
        Instant updatedAt
) {
}
