package com.cenk.valocase.common.exception;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cenk.valocase.progression.CategoryLockedException;
import com.cenk.valocase.progression.dto.CategoryLockedErrorResponse;

/**
 * Translates exceptions into a consistent {@link ErrorResponse} JSON body.
 * Kept intentionally small for Phase 1 foundation.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CategoryLockedException.class)
    public ResponseEntity<CategoryLockedErrorResponse> handleCategoryLocked(CategoryLockedException ex) {
        CategoryLockedErrorResponse body = new CategoryLockedErrorResponse(
                Instant.now(),
                ex.getStatus().value(),
                ex.getStatus().getReasonPhrase(),
                ex.getMessage(),
                ex.getCategory().name(),
                ex.getRequiredLevel(),
                ex.getCurrentLevel()
        );
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        return build(ex.getStatus(), ex.getMessage(), ex.getCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, String code) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                code
        );
        return ResponseEntity.status(status).body(body);
    }
}
