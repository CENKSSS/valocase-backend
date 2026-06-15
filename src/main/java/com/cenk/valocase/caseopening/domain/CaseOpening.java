package com.cenk.valocase.caseopening.domain;

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
 * Audit record of a single case opening. Its id is used as the referenceId on
 * the wallet debit transaction and as the caseOpeningId on the awarded
 * inventory item, linking the three together.
 */
@Entity
@Table(name = "case_openings")
@Getter
@Setter
@NoArgsConstructor
public class CaseOpening {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "case_id", length = 100, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "won_skin_id", length = 100, nullable = false, updatable = false)
    private String wonSkinId;

    @Column(name = "price_paid", nullable = false, updatable = false)
    private long pricePaid;

    @Column(name = "inventory_item_id")
    private UUID inventoryItemId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
