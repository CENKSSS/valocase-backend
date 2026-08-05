package com.cenk.valocase.telemetry.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One recorded step of the pre-account onboarding funnel.
 *
 * <p>Every column here is either a bounded allowlisted value or a client-generated
 * opaque id. There is deliberately no column for the nickname, the guest token,
 * an IP address, a device model or an advertising id, and no free-form JSON
 * column that could become one.
 */
@Entity
@Table(name = "onboarding_events")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Client-generated id for this one event. Unique in the database, which is
     * what makes a retry idempotent rather than duplicating the funnel step.
     */
    @Column(name = "event_id", nullable = false, updatable = false, length = 64)
    private String eventId;

    /**
     * Client-generated install identifier. Opaque, not derived from any device or
     * advertising identifier, and never written to a log in full.
     */
    @Column(name = "installation_id", nullable = false, updatable = false, length = 64)
    private String installationId;

    @Column(name = "event_name", nullable = false, updatable = false, length = 40)
    private String eventName;

    /**
     * When the client believes the event happened. Nullable and untrusted: device
     * clocks are wrong often enough that {@link #receivedAt} is the timestamp any
     * report should order or bucket by.
     */
    @Column(name = "client_timestamp_utc")
    private Instant clientTimestampUtc;

    /** Server clock at ingestion. The authoritative time for every report. */
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "app_version", length = 20)
    private String appVersion;

    @Column(name = "platform", length = 20)
    private String platform;

    /**
     * The country the player had selected when this step happened, uppercase
     * ISO-3166-1 alpha-2.
     *
     * <p>Accepted only on the steps that can meaningfully carry it — see
     * {@code OnboardingTelemetryService.countryCodeFor} — and dropped on every
     * other one, so the column never holds a value whose meaning depends on
     * which event brought it. Steps that do not carry it still get a country in
     * the reports, resolved from the installation's {@code country_selected}.
     *
     * <p>Never derived from an IP address. It is the player's own selection,
     * which is a fact about a choice, not about a location.
     */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    /** Only meaningful for {@code nickname_rejected}. */
    @Column(name = "rejection_reason", length = 30)
    private String rejectionReason;

    /** Only meaningful for {@code registration_failed}. */
    @Column(name = "network_error_category", length = 30)
    private String networkErrorCategory;

    /** Only meaningful for {@code registration_failed} with an HTTP response. */
    @Column(name = "http_status")
    private Integer httpStatus;
}
