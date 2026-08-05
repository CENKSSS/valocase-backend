package com.cenk.valocase.telemetry.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.cenk.valocase.telemetry.TelemetryProperties;

class InstallationRateLimiterTest {

    private static final Instant T0 = Instant.parse("2026-08-03T10:00:00Z");

    private static TelemetryProperties properties(int events, Duration window) {
        TelemetryProperties properties = new TelemetryProperties();
        properties.setRateLimitEvents(events);
        properties.setRateLimitWindow(window);
        return properties;
    }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        InstallationRateLimiter limiter =
                new InstallationRateLimiter(properties(3, Duration.ofMinutes(1)));

        assertTrue(limiter.tryAcquire("a", T0));
        assertTrue(limiter.tryAcquire("a", T0));
        assertTrue(limiter.tryAcquire("a", T0));
        assertFalse(limiter.tryAcquire("a", T0));
        assertFalse(limiter.tryAcquire("a", T0));
    }

    @Test
    void theWindowResets() {
        InstallationRateLimiter limiter =
                new InstallationRateLimiter(properties(2, Duration.ofMinutes(1)));

        assertTrue(limiter.tryAcquire("a", T0));
        assertTrue(limiter.tryAcquire("a", T0));
        assertFalse(limiter.tryAcquire("a", T0.plusSeconds(30)));

        // A minute after the window opened, the budget is fresh again.
        assertTrue(limiter.tryAcquire("a", T0.plusSeconds(60)));
    }

    @Test
    void installationsAreLimitedIndependently() {
        InstallationRateLimiter limiter =
                new InstallationRateLimiter(properties(1, Duration.ofMinutes(1)));

        assertTrue(limiter.tryAcquire("a", T0));
        assertFalse(limiter.tryAcquire("a", T0));
        // One noisy install must not spend another install's budget.
        assertTrue(limiter.tryAcquire("b", T0));
    }

    @Test
    void theTrackingMapStaysBoundedUnderIdRotation() {
        // The abuse control must not itself be the abuse vector: a caller
        // inventing a new installationId per request would otherwise grow this
        // map without limit.
        TelemetryProperties properties = properties(60, Duration.ofMinutes(1));
        properties.setRateLimitMaxTrackedInstallations(100);
        InstallationRateLimiter limiter = new InstallationRateLimiter(properties);

        for (int i = 0; i < 10_000; i++) {
            limiter.tryAcquire("rotating-" + i, T0);
        }

        assertTrue(limiter.trackedInstallations() <= 100,
                "tracked=" + limiter.trackedInstallations());
    }

    @Test
    void expiredEntriesAreShedBeforeLiveOnes() {
        TelemetryProperties properties = properties(60, Duration.ofMinutes(1));
        properties.setRateLimitMaxTrackedInstallations(10);
        InstallationRateLimiter limiter = new InstallationRateLimiter(properties);

        for (int i = 0; i < 10; i++) {
            limiter.tryAcquire("old-" + i, T0);
        }
        // Well past the window: these are all expired and should be the ones
        // dropped when the cap forces a shed.
        limiter.tryAcquire("fresh", T0.plusSeconds(600));

        assertTrue(limiter.trackedInstallations() <= 10);
        assertTrue(limiter.tryAcquire("fresh", T0.plusSeconds(600)));
    }
}
