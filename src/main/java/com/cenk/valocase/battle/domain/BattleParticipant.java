package com.cenk.valocase.battle.domain;

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
 * One participant in a battle. Index 0 is the user; 1..N-1 are bots.
 */
@Entity
@Table(name = "battle_participants")
@Getter
@Setter
@NoArgsConstructor
public class BattleParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "battle_id", nullable = false, updatable = false)
    private UUID battleId;

    @Column(name = "participant_index", nullable = false, updatable = false)
    private int participantIndex;

    @Column(name = "is_user", nullable = false, updatable = false)
    private boolean user;

    @Column(name = "name", length = 100, updatable = false)
    private String name;

    @Column(name = "total_vp", nullable = false, updatable = false)
    private long totalVp;
}
