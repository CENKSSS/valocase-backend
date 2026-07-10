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
 * One foreground interval of a precise client session. Start/resume open a
 * segment; pause/end close it explicitly; timeout closes it at the last observed
 * heartbeat with {@code estimated} true. Background time is the gap between
 * segments and is never part of a segment.
 */
@Entity
@Table(name = "player_session_segments")
@Getter
@Setter
@NoArgsConstructor
public class PlayerSessionSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 30)
    private String endReason;

    @Column(name = "is_estimated", nullable = false)
    private boolean estimated = false;
}
