package com.cenk.valocase.battle.domain;

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
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Header record of a public online battle lobby. Unlike the immutable
 * {@link Battle} (which records a finished bot battle), a lobby is mutable while
 * it fills up: real players join and the creator adds bots until every slot is
 * taken. When it resolves it reuses the existing battle rules and persists the
 * outcome into a {@link Battle} referenced by {@link #resultBattleId}.
 *
 * <p>Column types/nullability match the V37 migration so {@code ddl-auto=validate}
 * passes. A JPA {@code @Version} guards against lost updates on top of the
 * pessimistic row lock taken for every mutating action.
 */
@Entity
@Table(name = "battle_lobbies")
@Getter
@Setter
@NoArgsConstructor
public class BattleLobby {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "creator_account_id", nullable = false, updatable = false)
    private UUID creatorAccountId;

    @Column(name = "case_id", length = 100, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "rounds", nullable = false, updatable = false)
    private int rounds;

    /** Total slots = participant count (2 = 1v1, 3 = 1v1v1, 4 = 1v1v1v1). */
    @Column(name = "max_slots", nullable = false, updatable = false)
    private int maxSlots;

    /** Per-player entry cost (case price x rounds), charged once per real player. */
    @Column(name = "entry_cost", nullable = false, updatable = false)
    private long entryCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private LobbyStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When set (lobby full), the battle resolves once this instant has passed. */
    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Winning slot index, set only once the lobby is COMPLETED. */
    @Column(name = "winner_slot_index")
    private Integer winnerSlotIndex;

    /** The immutable {@link Battle} produced on resolution; null until COMPLETED. */
    @Column(name = "result_battle_id")
    private UUID resultBattleId;

    /** True for a system-created Free Lobby Event; clients can never set this. */
    @Column(name = "is_event", nullable = false, updatable = false)
    private boolean event;

    /** The 2-minute window an event lobby belongs to; UNIQUE so only one exists per window. */
    @Column(name = "event_window_key", length = 60, updatable = false)
    private String eventWindowKey;

    @Version
    @Column(name = "version", nullable = false)
    private long version;
}
