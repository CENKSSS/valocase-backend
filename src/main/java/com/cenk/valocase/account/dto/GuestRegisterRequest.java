package com.cenk.valocase.account.dto;

/**
 * Body of a guest registration. The player picks their nickname before the
 * account exists, so the name arrives with the request rather than being
 * generated and corrected afterwards.
 *
 * <p>{@code displayName} is required: a request without one creates nothing.
 */
public record GuestRegisterRequest(
        String displayName
) {
}
