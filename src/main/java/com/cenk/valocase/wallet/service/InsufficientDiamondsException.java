package com.cenk.valocase.wallet.service;

import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

/**
 * Thrown when a diamond debit would take a wallet below zero. Maps to HTTP 422.
 */
public class InsufficientDiamondsException extends ApiException {

    public static final String CODE = "INSUFFICIENT_DIAMONDS";

    public InsufficientDiamondsException(long balance, long requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient diamonds: balance " + balance + ", requested " + requested, CODE);
    }
}
