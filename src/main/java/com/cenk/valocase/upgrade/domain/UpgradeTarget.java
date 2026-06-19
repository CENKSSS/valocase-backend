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
 * Snapshot of one target skin selected for an upgrade attempt. vpValue captures
 * the target's value at attempt time; grantedInventoryItemId is set only when
 * the upgrade succeeds and this target was granted.
 */
@Entity
@Table(name = "upgrade_targets")
@Getter
@Setter
@NoArgsConstructor
public class UpgradeTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "upgrade_id", nullable = false, updatable = false)
    private UUID upgradeId;

    @Column(name = "skin_id", length = 100, nullable = false, updatable = false)
    private String skinId;

    @Column(name = "vp_value", nullable = false, updatable = false)
    private int vpValue;

    @Column(name = "granted_inventory_item_id")
    private UUID grantedInventoryItemId;
}
