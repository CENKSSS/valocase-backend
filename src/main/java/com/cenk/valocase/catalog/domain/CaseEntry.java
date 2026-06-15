package com.cenk.valocase.catalog.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One drop-pool entry: a skin belonging to a case with a relative weight.
 * Identified by a generated UUID; (caseId, skinId) is unique.
 *
 * caseId / skinId are stored as the plain Unity stable IDs (string FKs to
 * case_definitions / skins) — deliberately no JPA relationship mapping to keep
 * this phase simple.
 */
@Entity
@Table(
        name = "case_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_case_entries_case_skin",
                columnNames = {"case_id", "skin_id"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class CaseEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", length = 100, nullable = false)
    private String caseId;

    @Column(name = "skin_id", length = 100, nullable = false)
    private String skinId;

    @Column(name = "weight", nullable = false)
    private int weight;
}
