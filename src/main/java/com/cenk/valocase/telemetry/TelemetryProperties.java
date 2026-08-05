package com.cenk.valocase.telemetry;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Limits for the unauthenticated onboarding telemetry endpoint. Every one of
 * these is an abuse control, so they live in configuration rather than as
 * constants: a limit that turns out to be wrong in production must be adjustable
 * without a rebuild.
 */
@ConfigurationProperties(prefix = "valocase.telemetry")
@Getter
@Setter
public class TelemetryProperties {

    /** Master switch. When false the endpoint answers 404 and nothing is stored. */
    private boolean enabled = true;

    /** Hard cap on the request body, in bytes. Rejected before parsing. */
    private int maxBodyBytes = 2_048;

    /** Maximum accepted events per installationId per {@link #rateLimitWindow}. */
    private int rateLimitEvents = 60;

    /** Window for {@link #rateLimitEvents}. */
    private Duration rateLimitWindow = Duration.ofMinutes(1);

    /**
     * Upper bound on how many installationIds the limiter tracks at once.
     *
     * <p>This exists because the limiter is the abuse control and must not itself
     * become the abuse vector: a caller rotating installationId on every request
     * would otherwise grow the map without bound. When the cap is reached the
     * limiter sheds its oldest entries rather than allocating.
     */
    private int rateLimitMaxTrackedInstallations = 50_000;

    /** Maximum accepted length of installationId and eventId. */
    private int maxIdentifierLength = 64;

    /** Maximum accepted length of appVersion. */
    private int maxAppVersionLength = 20;
}
