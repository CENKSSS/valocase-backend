package com.cenk.valocase.common.diagnostics;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Prints {@link DiagnosticCounters} on a fixed interval.
 *
 * <p>The line is emitted even when every counter is zero, deliberately: a run of
 * {@code guest_registration_started=0} is itself the finding — it distinguishes
 * "no traffic reached the endpoint" from "traffic arrived and was refused", which
 * is exactly the distinction the logs could not previously make.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DiagnosticsReporter {

    private final DiagnosticCounters counters;

    @Scheduled(
            initialDelayString = "${valocase.diagnostics.report-interval:PT5M}",
            fixedDelayString = "${valocase.diagnostics.report-interval:PT5M}")
    void report() {
        log.info("diagnostics {}", counters.snapshot());
    }
}
