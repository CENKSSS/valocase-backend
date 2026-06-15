package com.cenk.valocase.daily.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-account daily streak state (one row per account). The primary key is the
 * account id (assigned, not generated).
 */
@Entity
@Table(name = "daily_reward_state")
@Getter
@Setter
@NoArgsConstructor
public class DailyRewardState {

    @Id
    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "last_claim_date", nullable = false)
    private LocalDate lastClaimDate;

    @Column(name = "current_streak", nullable = false)
    private int currentStreak;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
