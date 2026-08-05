package com.cenk.valocase.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Switches that govern what a registration request must carry.
 *
 * <p>These are rollout controls, not tuning knobs. A field becomes mandatory the
 * moment every client in the field sends it, and that moment is known from
 * store-release data rather than from a build — so it has to be flippable
 * without a redeploy of the code that reads it.
 */
@ConfigurationProperties(prefix = "valocase.registration")
@Getter
@Setter
public class RegistrationProperties {

    /**
     * Whether {@code countryCode} is mandatory on {@code POST /api/v1/guest}.
     *
     * <p><strong>Must stay false until the country-screen Unity release is the
     * only client in the field.</strong> The clients live in the store today
     * send {@code displayName} alone; turning this on before they are drained
     * makes every one of their registrations a 400 and stops sign-ups dead.
     *
     * <p>False is the migration window: a request without a country creates an
     * account whose {@code country_code} is NULL. Nothing is inferred, guessed,
     * or derived from an IP address to fill that gap. Once true, a request
     * without a valid country is refused and creates nothing.
     */
    private boolean requireCountryCode = false;
}
