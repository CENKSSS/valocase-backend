package com.cenk.valocase.account.dto;

/**
 * Body of a country change from the Settings screen.
 *
 * <p>The account is identified by the {@code X-Guest-Token} header and by
 * nothing else. There is deliberately no {@code accountId} component: a client
 * that could name the account it is editing could edit somebody else's.
 *
 * @param countryCode ISO-3166-1 alpha-2, any case; validated against the same
 *                    allowlist registration uses
 */
public record UpdateCountryRequest(
        String countryCode
) {
}
