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
 * An analytics event for gameplay facts that have no canonical table of their
 * own (e.g. per-item sell counts lost when bulk sells delete inventory rows).
 * Facts already recorded by canonical tables are never duplicated here.
 */
@Entity
@Table(name = "player_activity_events")
@Getter
@Setter
@NoArgsConstructor
public class PlayerActivityEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "event_type", length = 40, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "source", length = 50, updatable = false)
    private String source;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    @Column(name = "quantity", updatable = false)
    private Integer quantity;

    @Column(name = "vp_amount", updatable = false)
    private Long vpAmount;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}
