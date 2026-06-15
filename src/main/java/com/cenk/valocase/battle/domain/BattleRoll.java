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
 * One rolled skin in a battle (participant x round), with a vpValue snapshot.
 * granted_inventory_item_id is set only when the user wins and this roll's skin
 * is granted to the user's inventory.
 */
@Entity
@Table(name = "battle_rolls")
@Getter
@Setter
@NoArgsConstructor
public class BattleRoll {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "battle_id", nullable = false, updatable = false)
    private UUID battleId;

    @Column(name = "participant_index", nullable = false, updatable = false)
    private int participantIndex;

    @Column(name = "round_number", nullable = false, updatable = false)
    private int roundNumber;

    @Column(name = "skin_id", length = 100, nullable = false, updatable = false)
    private String skinId;

    @Column(name = "vp_value", nullable = false, updatable = false)
    private int vpValue;

    @Column(name = "granted_inventory_item_id")
    private UUID grantedInventoryItemId;
}
