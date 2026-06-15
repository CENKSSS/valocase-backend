package com.cenk.valocase.catalog.importer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One entry parsed from the Unity skins.json. Field names mirror the Unity
 * SkinCatalogEntry; unknown fields are ignored so extra Unity-side fields do
 * not break the import.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkinCatalogEntry {

    private String skinId;
    private String displayName;
    private String weapon;
    private String rarity;
    private int vpValue;
    private String resourceKey;
    private boolean enabled;
}
