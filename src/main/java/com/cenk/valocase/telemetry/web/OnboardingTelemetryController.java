package com.cenk.valocase.telemetry.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cenk.valocase.telemetry.TelemetryProperties;
import com.cenk.valocase.telemetry.dto.OnboardingEventAck;
import com.cenk.valocase.telemetry.dto.OnboardingEventRequest;
import com.cenk.valocase.telemetry.service.OnboardingTelemetryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Unauthenticated ingestion for pre-account onboarding events.
 *
 * <p>Unauthenticated by necessity: every event this endpoint exists to record
 * happens before an account, and therefore before a token, exists. That makes
 * the abuse controls part of the contract rather than a nicety — payload size
 * (see {@code OnboardingBodySizeFilter}), an event-name allowlist, per-field
 * length bounds, and a per-installation rate limit.
 *
 * <p>No status here ever depends on anything a player is doing elsewhere, and
 * the catch-all below guarantees the same in the other direction: a telemetry
 * failure returns 202 and is logged, because a client that cannot report a funnel
 * step must still be able to finish registering.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
@RequiredArgsConstructor
@Slf4j
public class OnboardingTelemetryController {

    private final OnboardingTelemetryService telemetryService;
    private final TelemetryProperties properties;

    /**
     * @return 202 when stored or already known, 400 when malformed,
     *         429 when rate limited
     */
    @PostMapping("/onboarding")
    public ResponseEntity<OnboardingEventAck> ingest(
            @RequestBody(required = false) OnboardingEventRequest request) {

        if (!properties.isEnabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        OnboardingTelemetryService.Result result;
        try {
            result = telemetryService.ingest(request);
        } catch (RuntimeException unexpected) {
            // Deliberately swallowed. This endpoint measures the funnel; it must
            // never become a reason a client retries, blocks, or fails to
            // register. The failure is logged and the client is told to move on.
            log.warn("telemetry ingestion threw; reporting accepted to the client", unexpected);
            return ResponseEntity.accepted().body(OnboardingEventAck.accepted());
        }

        return switch (result) {
            case ACCEPTED -> ResponseEntity.accepted().body(OnboardingEventAck.accepted());
            case DUPLICATE -> ResponseEntity.accepted().body(OnboardingEventAck.duplicate());
            case RATE_LIMITED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            case INVALID -> ResponseEntity.badRequest().build();
            // A storage failure is our problem, not the client's; 202 stops it
            // retrying into an outage it cannot help with.
            case ERROR -> ResponseEntity.accepted().body(OnboardingEventAck.accepted());
        };
    }
}
