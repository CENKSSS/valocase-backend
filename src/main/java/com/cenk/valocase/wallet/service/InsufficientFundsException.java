package com.cenk.valocase.wallet.service;

import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

/**
 * Thrown when a debit would take a wallet below zero. Maps to HTTP 422.
 */
public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(long balance, long requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds: balance " + balance + " VP, requested " + requested + " VP");
    }
}
