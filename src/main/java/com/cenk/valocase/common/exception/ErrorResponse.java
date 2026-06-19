package com.cenk.valocase.common.exception;

import java.time.Instant;

/**
 * Minimal, stable error body returned by {@link GlobalExceptionHandler}.
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String code
) {
}
