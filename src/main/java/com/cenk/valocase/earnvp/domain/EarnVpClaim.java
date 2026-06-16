package com.cenk.valocase.earnvp.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit record of one Earn VP session claim. Unique per account per
 * clientSessionId, so a resubmitted session is rejected at the database level.
 */
@Entity
@Table(
        name = "earn_vp_claims",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_earn_vp_claims_account_session",
                columnNames = {"account_id", "client_session_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class EarnVpClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "client_session_id", length = 100, nullable = false, updatable = false)
    private String clientSessionId;

    @Column(name = "tap_count_accepted", nullable = false, updatable = false)
    private int tapCountAccepted;

    @Column(name = "vp_granted", nullable = false, updatable = false)
    private long vpGranted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
