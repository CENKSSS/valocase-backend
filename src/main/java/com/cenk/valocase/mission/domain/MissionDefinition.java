package com.cenk.valocase.mission.domain;

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
 * A mission definition (seeded via Flyway). For v1 the period is ONE_TIME.
 */
@Entity
@Table(name = "mission_definitions")
@Getter
@Setter
@NoArgsConstructor
public class MissionDefinition {

    /** Period value for non-recurring missions; its progress period_key is "". */
    public static final String PERIOD_ONE_TIME = "ONE_TIME";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "code", length = 50, nullable = false, updatable = false)
    private String code;

    @Column(name = "title", length = 150, nullable = false)
    private String title;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Column(name = "target_count", nullable = false)
    private int targetCount;

    @Column(name = "reward_vp", nullable = false)
    private long rewardVp;

    @Column(name = "period", length = 20, nullable = false)
    private String period;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
