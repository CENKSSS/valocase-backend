package com.cenk.valocase.account.dto;

/**
 * Body of a guest registration. The player picks their nickname and country
 * before the account exists, so both arrive with the request rather than being
 * generated and corrected afterwards.
 *
 * <p>{@code displayName} is required: a request without one creates nothing.
 *
 * <p>{@code countryCode} is required only once
 * {@code valocase.registration.require-country-code} is on. Until then a request
 * without it still registers, and the account is stored with no country — which
 * is what keeps the Unity build already in the store working while the country
 * screen is still on its way. See {@code RegistrationProperties}.
 *
 * @param displayName the nickname the player confirmed
 * @param countryCode ISO-3166-1 alpha-2, any case; never a country name
 */
public record GuestRegisterRequest(
        String displayName,
        String countryCode
) {
}
