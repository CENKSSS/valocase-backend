package com.cenk.valocase.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base application exception carrying the HTTP status to return to the client.
 * Feature code throws this (or a subclass) for expected error conditions.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
