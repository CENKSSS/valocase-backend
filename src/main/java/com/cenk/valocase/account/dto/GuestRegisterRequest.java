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
 * <p>{@code installationId} is optional and analytics-only. It links the account
 * to the install that created it, closing the one gap
 * {@code player_sessions.installation_id} cannot cover: an account whose first
 * session never arrives. A missing or unparseable value is dropped and the
 * registration proceeds — see {@code AccountService#resolveInstallationId}.
 * Clients that predate the field (1.0.19 and 1.0.21, both live in the store)
 * simply omit it.
 *
 * @param displayName    the nickname the player confirmed
 * @param countryCode    ISO-3166-1 alpha-2, any case; never a country name
 * @param installationId the client's own per-install UUID; optional, never
 *                       echoed back, never used to authenticate
 */
public record GuestRegisterRequest(
        String displayName,
        String countryCode,
        String installationId
) {
}
