package com.cenk.valocase.catalog.importer;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One entry parsed from the Unity cases.json. Field names mirror the Unity
 * CaseCatalogEntry; unknown fields are ignored.
 *
 * manualDropPool is kept as raw values because each element may be either a
 * bare skinId string ("skin_x") or an object ({"skinId":"skin_x","weight":2}).
 * Jackson deserializes these to String or Map respectively; they are normalized
 * during import.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseCatalogEntry {

    private String caseId;
    private String displayName;
    private int price;
    private String resourceKey;
    private boolean enabled;
    private List<Object> manualDropPool;
    private List<RarityWeight> rarityWeights;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RarityWeight {
        private String rarity;
        private double weight;
    }
}
