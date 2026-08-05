package com.cenk.valocase.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.analytics.AnalyticsProperties;
import com.cenk.valocase.telemetry.TelemetryProperties;

@Configuration
@EnableConfigurationProperties({
        AnalyticsProperties.class,
        TelemetryProperties.class,
        RegistrationProperties.class})
public class AnalyticsConfig {
}
