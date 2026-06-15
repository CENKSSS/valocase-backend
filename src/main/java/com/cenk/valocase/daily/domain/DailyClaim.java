package com.cenk.valocase.daily.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import jakarta.persistence.UniqueConstraint;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit record of one claimed daily reward. Unique per account per UTC date, so
 * a second claim on the same date is rejected at the database level.
 */
@Entity
@Table(
        name = "daily_claims",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_daily_claims_account_date",
                columnNames = {"account_id", "claim_date"}
        )
)
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

    @Column(name = "claim_date", nullable = false, updatable = false)
    private LocalDate claimDate;

    @Column(name = "streak", nullable = false, updatable = false)
    private int streak;

    @Column(name = "reward_vp", nullable = false, updatable = false)
    private long rewardVp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
