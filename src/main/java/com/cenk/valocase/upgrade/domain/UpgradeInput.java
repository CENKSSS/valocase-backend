package com.cenk.valocase.upgrade.domain;

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
 * Snapshot of one inventory item consumed by an upgrade. The original inventory
 * row is deleted on consumption, so itemId has no FK; skinId and vpValue capture
 * the item's value at the moment it was burned.
 */
@Entity
@Table(name = "upgrade_inputs")
@Getter
@Setter
@NoArgsConstructor
public class UpgradeInput {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "upgrade_id", nullable = false, updatable = false)
    private UUID upgradeId;

    @Column(name = "item_id", nullable = false, updatable = false)
    private UUID itemId;

    @Column(name = "skin_id", length = 100, nullable = false, updatable = false)
    private String skinId;

    @Column(name = "vp_value", nullable = false, updatable = false)
    private int vpValue;
}
