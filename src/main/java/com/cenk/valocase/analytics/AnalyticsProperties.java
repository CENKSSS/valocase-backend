package com.cenk.valocase.analytics;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Tuning knobs for backend-only session estimation. sessionTimeout is the
 * inactivity gap that ends an estimated session; writeThrottle caps how often
 * rapid polling may update the open session row.
 */
@ConfigurationProperties(prefix = "valocase.analytics")
@Getter
@Setter
public class AnalyticsProperties {

    private Duration sessionTimeout = Duration.ofMinutes(5);

    private Duration writeThrottle = Duration.ofSeconds(30);
}
