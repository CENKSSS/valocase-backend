package com.cenk.valocase.telemetry.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import com.cenk.valocase.telemetry.TelemetryProperties;

import lombok.RequiredArgsConstructor;

/**
 * Fixed-window request limit per installationId, held in memory.
 *
 * <p>In memory, so it is per instance: two app instances each allow the
 * configured rate, and a restart forgets everything. That is accepted here.
 * This limit exists to stop a broken or malicious client from filling a table,
 * not to enforce a quota precisely, and the whole onboarding funnel is nine
 * events — a legitimate install cannot come close to the default of 60 per
 * minute. Platform-level IP limiting is the outer defence; see the deployment
 * notes.
 *
 * <p>The tracking map is capped. A limiter that grows one entry per distinct
 * installationId would hand an attacker an easy memory-exhaustion vector by
 * rotating the id on every request, so reaching the cap sheds existing entries
 * instead of allocating more. Shedding can briefly let a limited caller through;
 * that is the deliberate trade against unbounded growth.
 */
@Component
@RequiredArgsConstructor
public class InstallationRateLimiter {

    private final TelemetryProperties properties;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        private final AtomicInteger count = new AtomicInteger();
        private volatile Instant startedAt;

        private Window(Instant startedAt) {
            this.startedAt = startedAt;
        }
    }

    /**
     * @return true when this event is within the limit and may be processed
     */
    public boolean tryAcquire(String installationId, Instant now) {
        Duration window = properties.getRateLimitWindow();
        int limit = properties.getRateLimitEvents();

        if (windows.size() >= properties.getRateLimitMaxTrackedInstallations()) {
            shedExpired(now, window);
        }

        Window state = windows.compute(installationId, (key, existing) -> {
            if (existing == null) {
                return new Window(now);
            }
            if (Duration.between(existing.startedAt, now).compareTo(window) >= 0) {
                existing.startedAt = now;
                existing.count.set(0);
            }
            return existing;
        });

        return state.count.incrementAndGet() <= limit;
    }

    /**
     * Drops windows that have already expired. Called only when the cap is hit,
     * so the common path stays allocation-free. If nothing has expired, the map
     * is cleared outright rather than growing past the cap.
     */
    private void shedExpired(Instant now, Duration window) {
        int before = windows.size();
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> entry = it.next();
            if (Duration.between(entry.getValue().startedAt, now).compareTo(window) >= 0) {
                it.remove();
            }
        }
        if (windows.size() >= before) {
            windows.clear();
        }
    }

    /** Visible for tests and diagnostics. */
    public int trackedInstallations() {
        return windows.size();
    }
}
