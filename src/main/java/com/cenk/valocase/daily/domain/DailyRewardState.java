package com.cenk.valocase.daily.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Per-account daily reward state (one row per account). The primary key is the
 * account id (assigned, not generated). last_claim_at drives the 24h cooldown.
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

    @Column(name = "last_claim_at", nullable = false)
    private Instant lastClaimAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
