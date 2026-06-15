package com.cenk.valocase.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base application exception carrying the HTTP status to return to the client.
 * Feature code throws this (or a subclass) for expected error conditions.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
