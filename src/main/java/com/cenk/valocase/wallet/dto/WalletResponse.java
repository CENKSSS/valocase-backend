package com.cenk.valocase.wallet.dto;

import java.time.Instant;

import com.cenk.valocase.progression.dto.ProgressionView;

/**
 * Current wallet state for the authenticated guest. This is the endpoint the
 * client calls on startup, so it also carries the player's progression snapshot
 * (null only on internal balance-only reads).
 */
public record WalletResponse(
        String accountId,
        long vpBalance,
        Instant updatedAt,
        ProgressionView progression
) {
}
