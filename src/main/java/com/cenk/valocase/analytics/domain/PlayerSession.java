package com.cenk.valocase.analytics.domain;

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
 * A server-estimated play session derived from authenticated request activity.
 * The client sends no heartbeat or logout, so a session ends only by
 * inactivity timeout and {@code isEstimated} is always true.
 */
@Entity
@Table(name = "player_sessions")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Long durationSeconds;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "is_estimated", nullable = false)
    private boolean estimated = true;

    @Column(name = "platform", length = 30)
    private String platform;

    @Column(name = "app_version", length = 50)
    private String appVersion;

    @Column(name = "client_session_id")
    private UUID clientSessionId;

    @Column(name = "installation_id")
    private UUID installationId;

    @Column(name = "lifecycle_sequence")
    private Long lifecycleSequence;

    @Column(name = "lifecycle_state", length = 20)
    private String lifecycleState;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "explicit_ended_at")
    private Instant explicitEndedAt;
}
