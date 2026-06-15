package com.cenk.valocase.inventory.domain;

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
 * A single owned skin instance. Inventory is per-instance: there is NO quantity
 * field — owning the same skin twice means two separate rows.
 *
 * skinId is the Unity stable skin id (string FK to skins). caseOpeningId is set
 * later when items are awarded by case opening; null otherwise.
 */
@Entity
@Table(name = "inventory_items")
@Getter
@Setter
@NoArgsConstructor
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "skin_id", length = 100, nullable = false, updatable = false)
    private String skinId;

    @Column(name = "source", length = 50, nullable = false, updatable = false)
    private String source;

    @Column(name = "acquired_at", nullable = false, updatable = false)
    private Instant acquiredAt;

    @Column(name = "case_opening_id", updatable = false)
    private UUID caseOpeningId;
}
