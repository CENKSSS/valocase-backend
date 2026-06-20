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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One slot of a {@link BattleLobby}. Slot 0 is always the creator. A slot starts
 * {@code EMPTY} and is filled either by a real player (join) or a bot (Add Bot).
 *
 * <p>{@code chargedVp} records what the occupant actually paid so a cancellation
 * can refund exactly that (bots and empty slots pay nothing). The
 * {@code (lobby_id, account_id)} uniqueness in V37 prevents a player joining the
 * same lobby twice (nulls do not collide, so multiple empty/bot slots are fine).
 */
@Entity
@Table(name = "battle_lobby_slots")
@Getter
@Setter
@NoArgsConstructor
public class BattleLobbySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lobby_id", nullable = false, updatable = false)
    private UUID lobbyId;

    @Column(name = "slot_index", nullable = false, updatable = false)
    private int slotIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot_type", length = 10, nullable = false)
    private SlotType slotType;

    /** Account of a real-player occupant; null for EMPTY and BOT slots. */
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "avatar_id", length = 50)
    private String avatarId;

    @Column(name = "is_creator", nullable = false)
    private boolean creator;

    /** VP actually charged to this occupant (0 for empty/bot slots). */
    @Column(name = "charged_vp", nullable = false)
    private long chargedVp;

    /**
     * Last time a real-player occupant was seen (set on create/join, refreshed
     * on each status poll). Used at resolution to decide reward eligibility:
     * a winner not seen within the connection window is treated as disconnected
     * and receives no reward. NULL for empty/bot slots.
     */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;
}
