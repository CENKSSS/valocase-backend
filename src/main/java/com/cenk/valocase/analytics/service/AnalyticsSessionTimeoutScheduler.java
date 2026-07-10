package com.cenk.valocase.analytics.service;

import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Closes precise client sessions whose heartbeat stopped without a graceful end
 * (force-close, crash, OS kill). Each is closed at its last server-observed
 * activity with is_estimated true; no client exit time is invented.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsSessionTimeoutScheduler {

    private final ClientSessionService clientSessionService;

    @Scheduled(
            fixedDelayString = "${valocase.analytics.timeout-scan-interval:PT30S}",
            initialDelayString = "${valocase.analytics.timeout-scan-interval:PT30S}")
    public void run() {
        for (UUID id : clientSessionService.staleOpenClientSessionIds()) {
            try {
                clientSessionService.closeStaleSession(id);
            } catch (RuntimeException ex) {
                log.warn("Failed to close stale client session {}: {}", id, ex.getMessage());
            }
        }
    }
}
