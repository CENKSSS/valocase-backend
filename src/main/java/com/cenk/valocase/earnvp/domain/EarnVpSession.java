package com.cenk.valocase.earnvp.domain;

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
 * Server-authoritative Earn VP session. {@code startedAt} is set from the server
 * clock when the session starts; the claim derives elapsed duration from it so
 * client-reported timing is never trusted.
 */
@Entity
@Table(name = "earn_vp_sessions")
@Getter
@Setter
@NoArgsConstructor
public class EarnVpSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "bonus2x_active", nullable = false)
    private boolean bonus2xActive;

    @Column(name = "bonus2x_expires_at")
    private Instant bonus2xExpiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
