package com.cenk.valocase.telemetry.web;

import java.io.IOException;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.telemetry.TelemetryProperties;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Rejects oversized bodies on the telemetry path before anything parses them.
 *
 * <p>Scoped to {@code /api/v1/telemetry/**} on purpose. This is the one
 * unauthenticated write endpoint in the service, so it is the one that needs a
 * body bound; applying the same limit to gameplay endpoints would break legitimate
 * large payloads such as inventory operations.
 *
 * <p>Two checks, because either alone has a hole. {@code Content-Length} is free
 * but absent on a chunked request, so a caller could omit it; a streaming counter
 * catches that but only after bytes arrive. Together they bound both shapes, and
 * the limit is small enough (2 KB by default, against a real payload near 200
 * bytes) that the streaming path stops an abusive request early.
 */
@Component
@Order(1)
@RequiredArgsConstructor
public class OnboardingBodySizeFilter extends OncePerRequestFilter {

    private static final String TELEMETRY_PREFIX = "/api/v1/telemetry/";

    private final TelemetryProperties properties;
    private final DiagnosticCounters counters;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(TELEMETRY_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        int max = properties.getMaxBodyBytes();

        if (request.getContentLengthLong() > max) {
            counters.recordTelemetryRejected("BODY_TOO_LARGE");
            response.sendError(HttpStatus.BAD_REQUEST.value());
            return;
        }

        chain.doFilter(new LimitedBodyRequest(request, max, counters), response);
    }

    /** Wraps the body stream so a chunked request cannot exceed the limit either. */
    private static final class LimitedBodyRequest extends jakarta.servlet.http.HttpServletRequestWrapper {

        private final int max;
        private final DiagnosticCounters counters;

        private LimitedBodyRequest(HttpServletRequest request, int max, DiagnosticCounters counters) {
            super(request);
            this.max = max;
            this.counters = counters;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() throws IOException {
            jakarta.servlet.ServletInputStream delegate = super.getInputStream();
            return new jakarta.servlet.ServletInputStream() {
                private int read;

                private int count(int b) throws IOException {
                    if (b >= 0 && ++read > max) {
                        counters.recordTelemetryRejected("BODY_TOO_LARGE");
                        throw new IOException("telemetry body exceeds " + max + " bytes");
                    }
                    return b;
                }

                @Override
                public int read() throws IOException {
                    return count(delegate.read());
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = delegate.read(buffer, off, len);
                    if (n > 0) {
                        read += n;
                        if (read > max) {
                            counters.recordTelemetryRejected("BODY_TOO_LARGE");
                            throw new IOException("telemetry body exceeds " + max + " bytes");
                        }
                    }
                    return n;
                }

                @Override
                public boolean isFinished() {
                    return delegate.isFinished();
                }

                @Override
                public boolean isReady() {
                    return delegate.isReady();
                }

                @Override
                public void setReadListener(jakarta.servlet.ReadListener listener) {
                    delegate.setReadListener(listener);
                }
            };
        }
    }
}
