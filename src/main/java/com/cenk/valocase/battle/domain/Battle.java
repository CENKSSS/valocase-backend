package com.cenk.valocase.battle.domain;

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
 * Header record of a single bot battle. Its id is the wallet debit referenceId
 * and the parent of battle_participants / battle_rolls.
 */
@Entity
@Table(name = "battles")
@Getter
@Setter
@NoArgsConstructor
public class Battle {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "case_id", length = 100, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "rounds", nullable = false, updatable = false)
    private int rounds;

    @Column(name = "participant_count", nullable = false, updatable = false)
    private int participantCount;

    @Column(name = "entry_cost", nullable = false, updatable = false)
    private long entryCost;

    @Column(name = "winner_index", nullable = false, updatable = false)
    private int winnerIndex;

    @Column(name = "user_won", nullable = false, updatable = false)
    private boolean userWon;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
