package com.cenk.valocase.progression.dto;

import java.time.Instant;

/**
 * Error body for an attempt to open a locked case category. Extends the shape of
 * the standard error response with unlock details.
 */
public record CategoryLockedErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String category,
        int requiredLevel,
        int currentLevel
) {
}
