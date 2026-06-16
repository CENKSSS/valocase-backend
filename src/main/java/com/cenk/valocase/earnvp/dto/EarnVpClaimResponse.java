package com.cenk.valocase.earnvp.dto;

/**
 * Server-authoritative result of an Earn VP session claim.
 */
public record EarnVpClaimResponse(
        long vpGranted,
        long newBalance,
        int acceptedTapCount,
        String message
) {
}
