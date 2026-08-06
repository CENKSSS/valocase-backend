package com.cenk.valocase.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple liveness endpoint used to confirm the backend is running.
 * Not part of any feature domain and not protected by auth.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    /**
     * The version the store is serving, echoed so an <em>unauthenticated</em> client can
     * still learn it. The same value goes out on {@code /wallet}, but that endpoint needs a
     * guest token — which is exactly what an outdated or broken client may not have. A
     * player whose token stopped working would otherwise be held in a game that cannot
     * authenticate, with no way to be told that updating is what fixes it.
     *
     * <p>Empty (the default) means no client is ever asked to update. That is the off
     * switch for a wall the client now refuses to dismiss, so it stays the default.
     */
    @Value("${valocase.client.latest-version:}")
    private String latestClientVersion;

    @GetMapping("/health")
    public Map<String, String> health() {
        // LinkedHashMap rather than Map.of: the value may be absent, and Map.of rejects
        // nulls. An omitted key is also what older clients already expect to see.
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("service", "valocase-backend");
        if (latestClientVersion != null && !latestClientVersion.isBlank()) {
            body.put("latestVersion", latestClientVersion.trim());
        }
        return body;
    }
}
