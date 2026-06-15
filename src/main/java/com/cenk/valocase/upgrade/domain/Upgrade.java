package com.cenk.valocase.upgrade.domain;

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
 * Audit record of a single upgrade attempt (success or failure). Its id ties
 * together the consumed {@code upgrade_inputs} rows and, on success, the granted
 * inventory item.
 */
@Entity
@Table(name = "upgrades")
@Getter
@Setter
@NoArgsConstructor
public class Upgrade {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "target_skin_id", length = 100, nullable = false, updatable = false)
    private String targetSkinId;

    @Column(name = "input_count", nullable = false, updatable = false)
    private int inputCount;

    @Column(name = "input_value", nullable = false, updatable = false)
    private long inputValue;

    @Column(name = "target_value", nullable = false, updatable = false)
    private long targetValue;

    @Column(name = "chance", nullable = false, updatable = false)
    private double chance;

    @Column(name = "success", nullable = false, updatable = false)
    private boolean success;

    @Column(name = "granted_inventory_item_id")
    private UUID grantedInventoryItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
