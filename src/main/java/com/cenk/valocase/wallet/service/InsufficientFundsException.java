package com.cenk.valocase.wallet.service;

import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;

/**
 * Thrown when a debit would take a wallet below zero. Maps to HTTP 422.
 *
 * <p>422 is not exclusive to running out of VP — selling nothing, claiming an
 * incomplete mission, an invalid upgrade target and several ad-reward states all
 * return it too. A client must therefore decide to show "you cannot afford this"
 * from {@link #CODE}, never from the status alone, or it will say that for
 * unrelated failures.
 */
public class InsufficientFundsException extends ApiException {

    public static final String CODE = "INSUFFICIENT_FUNDS";

    public InsufficientFundsException(long balance, long requested) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds: balance " + balance + " VP, requested " + requested + " VP", CODE);
    }
}
