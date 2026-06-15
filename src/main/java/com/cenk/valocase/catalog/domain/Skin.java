package com.cenk.valocase.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A weapon skin. The primary key is the Unity stable skinId and is stored
 * verbatim (no generation, no renaming, no ASCII cleaning).
 */
@Entity
@Table(name = "skins")
@Getter
@Setter
@NoArgsConstructor
public class Skin {

    @Id
    @Column(name = "id", length = 100, nullable = false, updatable = false)
    private String id;

    @Column(name = "display_name", length = 150, nullable = false)
    private String displayName;

    @Column(name = "weapon", length = 100, nullable = false)
    private String weapon;

    @Column(name = "rarity", length = 50, nullable = false)
    private String rarity;

    @Column(name = "vp_value", nullable = false)
    private int vpValue;

    @Column(name = "image_ref", length = 255)
    private String imageRef;

    @Column(name = "active", nullable = false)
    private boolean active;
}
