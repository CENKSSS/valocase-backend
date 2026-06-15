package com.cenk.valocase.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A case definition. The primary key is the Unity stable caseId and is stored
 * verbatim (no generation, no renaming, no ASCII cleaning).
 */
@Entity
@Table(name = "case_definitions")
@Getter
@Setter
@NoArgsConstructor
public class CaseDefinition {

    @Id
    @Column(name = "id", length = 100, nullable = false, updatable = false)
    private String id;

    @Column(name = "display_name", length = 150, nullable = false)
    private String displayName;

    @Column(name = "price_vp", nullable = false)
    private int priceVp;

    @Column(name = "image_ref", length = 255)
    private String imageRef;

    @Column(name = "active", nullable = false)
    private boolean active;
}
