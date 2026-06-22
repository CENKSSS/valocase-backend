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
 * One authored rarity weight for a case (the Unity CaseManager value). Drives
 * rarity-first roll odds; {@code (caseId, rarity)} is unique. caseId is the plain
 * Unity stable case id (string FK to case_definitions).
 */
@Entity
@Table(
        name = "case_rarity_weights",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_case_rarity_weights_case_rarity",
                columnNames = {"case_id", "rarity"}
        )
)
@Getter
@Setter
@NoArgsConstructor
public class CaseRarityWeight {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", length = 100, nullable = false)
    private String caseId;

    @Column(name = "rarity", length = 50, nullable = false)
    private String rarity;

    @Column(name = "weight", nullable = false)
    private double weight;
}
