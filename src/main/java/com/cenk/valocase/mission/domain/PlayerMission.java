package com.cenk.valocase.mission.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A player's progress on a mission. For ONE_TIME missions periodKey is "" so
 * there is one row per account per mission, enforced by the unique constraint.
 */
@Entity
@Table(
        name = "player_missions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_player_missions",
                columnNames = {"account_id", "mission_id", "period_key"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class PlayerMission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "mission_id", nullable = false, updatable = false)
    private UUID missionId;

    @Column(name = "period_key", length = 40, nullable = false, updatable = false)
    private String periodKey;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private MissionStatus status;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "next_reset_at")
    private Instant nextResetAt;

    @Column(name = "reward_vp", nullable = false)
    private long rewardVp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public boolean isCooldownExpired(Instant now) {
        return status == MissionStatus.CLAIMED && nextResetAt != null && !now.isBefore(nextResetAt);
    }

    public void resetForNewCycle(Instant now) {
        this.progress = 0;
        this.status = MissionStatus.IN_PROGRESS;
        this.completedAt = null;
        this.claimedAt = null;
        this.nextResetAt = null;
        this.updatedAt = now;
    }
}
