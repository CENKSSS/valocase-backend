package com.cenk.valocase.daily.domain;

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
 * Audit record of one claimed daily reward.
 */
@Entity
@Table(name = "daily_claims")
@Getter
@Setter
@NoArgsConstructor
public class DailyClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "reward_vp", nullable = false, updatable = false)
    private long rewardVp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
