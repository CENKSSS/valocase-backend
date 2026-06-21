package com.cenk.valocase.adreward.domain;

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
 * Audit record of one rewarded-ad claim. Unique per account per reward type per
 * adToken, so replaying the same completed ad is rejected at the database level.
 * For UPGRADE_PLUS_5 the row stays unconsumed until the next upgrade attempt
 * consumes the buff; for EARN_VP_2X it is consumed at grant time.
 */
@Entity
@Table(
        name = "ad_reward_claims",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_ad_reward_claims_account_type_token",
                columnNames = {"account_id", "reward_type", "ad_token"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class AdRewardClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", length = 32, nullable = false, updatable = false)
    private AdRewardType rewardType;

    @Column(name = "ad_token", length = 100, nullable = false, updatable = false)
    private String adToken;

    @Column(name = "granted_vp")
    private Long grantedVp;

    @Column(name = "buff_percent")
    private Double buffPercent;

    @Column(name = "consumed", nullable = false)
    private boolean consumed;

    @Column(name = "source_ref", length = 64, updatable = false)
    private String sourceRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;
}
