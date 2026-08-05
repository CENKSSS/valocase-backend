package com.cenk.valocase.account.domain;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A player account. Phase 1 only supports anonymous guest accounts, identified
 * to the client by an opaque {@code guestToken} (a simple bearer token).
 */
@Entity
@Table(name = "accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "guest_token", nullable = false, unique = true, updatable = false)
    private UUID guestToken;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_id", length = 50)
    private String avatarId;

    /**
     * The country the player selected, as an ISO-3166-1 alpha-2 code, uppercase.
     *
     * <p>Nullable, and that is a rollout state rather than a permanent one: every
     * account created before the country screen shipped has no country, and no
     * value is invented for those rows. It is never derived from an IP address.
     *
     * <p>Self-reported and unverified — see {@code docs/API_CONTRACT.md}. A
     * player may pick a country they do not live in, and may change it later from
     * Settings, so this is a preference, not a fact about where they are.
     */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /** Current player level. Starts at 1. */
    @Column(name = "level", nullable = false)
    private int level = 1;

    /** XP accumulated toward the next level (leftover is kept on level up). */
    @Column(name = "current_level_xp", nullable = false)
    private int currentLevelXp = 0;

    /** Lifetime XP ever earned. */
    @Column(name = "total_xp", nullable = false)
    private long totalXp = 0L;
}
