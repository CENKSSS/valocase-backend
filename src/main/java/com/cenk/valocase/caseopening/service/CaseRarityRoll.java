package com.cenk.valocase.caseopening.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.CaseRarityWeight;
import com.cenk.valocase.catalog.domain.Skin;

import lombok.RequiredArgsConstructor;

/**
 * Rarity-first case roll: rarity is chosen by the authored CaseManager weights,
 * then a skin is chosen within the selected rarity bucket. Pool composition (how
 * many skins a rarity has) never distorts the rarity odds.
 *
 * Only rarities that have a positive authored weight AND at least one eligible
 * skin form a bucket; weights are renormalized across those buckets, so a
 * weighted-but-empty rarity is safely excluded.
 */
@Component
@RequiredArgsConstructor
public class CaseRarityRoll {

    static final List<String> RARITY_ORDER =
            List.of("Select", "Deluxe", "Premium", "Exclusive", "Ultra", "Melee");

    private final DropSelector dropSelector;
    private final CaseRng rng;

    public record RarityBucket(String rarity, double weight, double normalizedChance, List<CaseEntry> entries) {
    }

    public List<RarityBucket> activeBuckets(List<CaseEntry> eligible, Map<String, Skin> skinsById,
                                            List<CaseRarityWeight> weights) {
        if (weights == null || weights.isEmpty() || eligible.isEmpty()) {
            return List.of();
        }

        Map<String, Double> weightByRarity = new LinkedHashMap<>();
        for (CaseRarityWeight w : weights) {
            if (w.getWeight() > 0) {
                weightByRarity.merge(w.getRarity(), w.getWeight(), Double::sum);
            }
        }

        Map<String, List<CaseEntry>> entriesByRarity = new LinkedHashMap<>();
        for (CaseEntry entry : eligible) {
            Skin skin = skinsById.get(entry.getSkinId());
            if (skin != null && weightByRarity.containsKey(skin.getRarity())) {
                entriesByRarity.computeIfAbsent(skin.getRarity(), k -> new ArrayList<>()).add(entry);
            }
        }

        double total = entriesByRarity.keySet().stream().mapToDouble(weightByRarity::get).sum();
        if (total <= 0.0) {
            return List.of();
        }

        List<RarityBucket> buckets = new ArrayList<>();
        for (String rarity : orderedRarities(entriesByRarity.keySet())) {
            double weight = weightByRarity.get(rarity);
            buckets.add(new RarityBucket(rarity, weight, weight / total, entriesByRarity.get(rarity)));
        }
        return buckets;
    }

    public CaseEntry select(List<RarityBucket> buckets) {
        double total = buckets.stream().mapToDouble(RarityBucket::weight).sum();
        double roll = rng.nextDouble() * total;
        double cumulative = 0.0;
        for (RarityBucket bucket : buckets) {
            cumulative += bucket.weight();
            if (roll < cumulative) {
                return dropSelector.selectWeighted(bucket.entries());
            }
        }
        return dropSelector.selectWeighted(buckets.get(buckets.size() - 1).entries());
    }

    /** skinId -> normalizedRarityChance * normalizedWithinRaritySkinChance. */
    public Map<String, Double> skinDropChances(List<RarityBucket> buckets) {
        Map<String, Double> chances = new LinkedHashMap<>();
        for (RarityBucket bucket : buckets) {
            long bucketWeight = bucket.entries().stream().mapToLong(CaseEntry::getWeight).sum();
            for (CaseEntry entry : bucket.entries()) {
                double within = bucketWeight > 0 ? (double) entry.getWeight() / bucketWeight : 0.0;
                chances.put(entry.getSkinId(), round6(bucket.normalizedChance() * within));
            }
        }
        return chances;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static List<String> orderedRarities(java.util.Set<String> present) {
        List<String> ordered = new ArrayList<>();
        for (String rarity : RARITY_ORDER) {
            if (present.contains(rarity)) {
                ordered.add(rarity);
            }
        }
        for (String rarity : present) {
            if (!ordered.contains(rarity)) {
                ordered.add(rarity);
            }
        }
        return ordered;
    }
}
