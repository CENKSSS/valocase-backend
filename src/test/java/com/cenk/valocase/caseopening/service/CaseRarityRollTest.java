package com.cenk.valocase.caseopening.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cenk.valocase.caseopening.service.CaseRarityRoll.RarityBucket;
import com.cenk.valocase.catalog.domain.CaseEntry;
import com.cenk.valocase.catalog.domain.CaseRarityWeight;
import com.cenk.valocase.catalog.domain.Skin;

class CaseRarityRollTest {

    private final double[] roll = {0.0};
    private final CaseRarityRoll caseRarityRoll = new CaseRarityRoll(new DropSelector(), () -> roll[0]);

    private static CaseRarityWeight weight(String rarity, double w) {
        CaseRarityWeight rw = new CaseRarityWeight();
        rw.setRarity(rarity);
        rw.setWeight(w);
        return rw;
    }

    private static CaseEntry entry(String skinId, int weight) {
        CaseEntry e = new CaseEntry();
        e.setCaseId("case_x");
        e.setSkinId(skinId);
        e.setWeight(weight);
        return e;
    }

    private static Skin skin(String id, String rarity) {
        Skin s = new Skin();
        s.setId(id);
        s.setRarity(rarity);
        s.setActive(true);
        return s;
    }

    private static Map<String, Skin> skins(Skin... s) {
        Map<String, Skin> m = new java.util.HashMap<>();
        for (Skin x : s) {
            m.put(x.getId(), x);
        }
        return m;
    }

    private static RarityBucket bucket(List<RarityBucket> buckets, String rarity) {
        return buckets.stream().filter(b -> b.rarity().equals(rarity)).findFirst().orElseThrow();
    }

    @Test
    void rarityChancesFollowAuthoredWeights_notPoolComposition() {
        // Select 47, Deluxe 25, Melee 28; Premium/Exclusive/Ultra have zero weight.
        List<CaseRarityWeight> weights = List.of(
                weight("Select", 47), weight("Deluxe", 25), weight("Premium", 0),
                weight("Exclusive", 0), weight("Ultra", 0), weight("Melee", 28));

        // Melee bucket is heavily over-represented in the pool (100 skins) vs 1 each.
        List<CaseEntry> eligible = new ArrayList<>();
        eligible.add(entry("sel_1", 1));
        eligible.add(entry("del_1", 1));
        List<Skin> skinList = new ArrayList<>(List.of(skin("sel_1", "Select"), skin("del_1", "Deluxe")));
        for (int i = 0; i < 100; i++) {
            eligible.add(entry("melee_" + i, 1));
            skinList.add(skin("melee_" + i, "Melee"));
        }
        Map<String, Skin> skins = skins(skinList.toArray(new Skin[0]));

        List<RarityBucket> buckets = caseRarityRoll.activeBuckets(eligible, skins, weights);

        assertEquals(3, buckets.size());
        assertEquals(0.47, bucket(buckets, "Select").normalizedChance(), 1e-9);
        assertEquals(0.25, bucket(buckets, "Deluxe").normalizedChance(), 1e-9);
        // 100 Melee skins do not inflate the Melee rarity odds.
        assertEquals(0.28, bucket(buckets, "Melee").normalizedChance(), 1e-9);
    }

    @Test
    void meleeIsSeparateBucketFromExclusive() {
        List<CaseRarityWeight> weights = List.of(weight("Exclusive", 60), weight("Melee", 40));
        List<CaseEntry> eligible = List.of(entry("exc_1", 1), entry("melee_1", 1));
        Map<String, Skin> skins = skins(skin("exc_1", "Exclusive"), skin("melee_1", "Melee"));

        List<RarityBucket> buckets = caseRarityRoll.activeBuckets(eligible, skins, weights);

        assertEquals(2, buckets.size());
        assertEquals(0.60, bucket(buckets, "Exclusive").normalizedChance(), 1e-9);
        assertEquals(0.40, bucket(buckets, "Melee").normalizedChance(), 1e-9);
        assertTrue(bucket(buckets, "Exclusive").entries().stream().noneMatch(
                e -> bucket(buckets, "Melee").entries().contains(e)));
    }

    @Test
    void emptyWeightedRarityBucketIsExcludedAndRemainingRenormalized() {
        // Ultra has weight but no Ultra skin in the pool; it must be dropped and the
        // rest renormalized to sum to 1.
        List<CaseRarityWeight> weights = List.of(weight("Select", 50), weight("Ultra", 50));
        List<CaseEntry> eligible = List.of(entry("sel_1", 1));
        Map<String, Skin> skins = skins(skin("sel_1", "Select"));

        List<RarityBucket> buckets = caseRarityRoll.activeBuckets(eligible, skins, weights);

        assertEquals(1, buckets.size());
        assertEquals("Select", buckets.get(0).rarity());
        assertEquals(1.0, buckets.get(0).normalizedChance(), 1e-9);
    }

    @Test
    void noUsableBucket_returnsEmptyForFlatFallback() {
        // Authored weights for rarities absent from the pool (e.g. melee_case in cases.json).
        List<CaseRarityWeight> weights = List.of(weight("Select", 10), weight("Exclusive", 25));
        List<CaseEntry> eligible = List.of(entry("melee_1", 1), entry("melee_2", 1));
        Map<String, Skin> skins = skins(skin("melee_1", "Melee"), skin("melee_2", "Melee"));

        assertTrue(caseRarityRoll.activeBuckets(eligible, skins, weights).isEmpty());
        assertTrue(caseRarityRoll.activeBuckets(eligible, skins, List.of()).isEmpty());
    }

    @Test
    void selectRollsRarityFirstUsingWeights() {
        List<CaseRarityWeight> weights = List.of(
                weight("Select", 47), weight("Deluxe", 25), weight("Melee", 28));
        List<CaseEntry> eligible = List.of(entry("sel_1", 1), entry("del_1", 1), entry("melee_1", 1));
        Map<String, Skin> skins = skins(
                skin("sel_1", "Select"), skin("del_1", "Deluxe"), skin("melee_1", "Melee"));
        List<RarityBucket> buckets = caseRarityRoll.activeBuckets(eligible, skins, weights);

        // total weight = 100; roll positions map into [Select 0-47)(Deluxe 47-72)(Melee 72-100).
        roll[0] = 0.10;
        assertEquals("sel_1", caseRarityRoll.select(buckets).getSkinId());
        roll[0] = 0.60;
        assertEquals("del_1", caseRarityRoll.select(buckets).getSkinId());
        roll[0] = 0.90;
        assertEquals("melee_1", caseRarityRoll.select(buckets).getSkinId());
    }

    @Test
    void skinDropChanceIsRarityChanceTimesWithinRarityChance() {
        List<CaseRarityWeight> weights = List.of(weight("Select", 47), weight("Melee", 28), weight("Deluxe", 25));
        List<CaseEntry> eligible = List.of(
                entry("sel_1", 1), entry("del_1", 1), entry("melee_1", 1), entry("melee_2", 1));
        Map<String, Skin> skins = skins(
                skin("sel_1", "Select"), skin("del_1", "Deluxe"),
                skin("melee_1", "Melee"), skin("melee_2", "Melee"));
        List<RarityBucket> buckets = caseRarityRoll.activeBuckets(eligible, skins, weights);

        Map<String, Double> chances = caseRarityRoll.skinDropChances(buckets);

        assertEquals(0.47, chances.get("sel_1"), 1e-9);
        assertEquals(0.25, chances.get("del_1"), 1e-9);
        // Two equally-weighted Melee skins split the 0.28 rarity chance.
        assertEquals(0.14, chances.get("melee_1"), 1e-9);
        assertEquals(0.14, chances.get("melee_2"), 1e-9);
        double total = chances.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, total, 1e-9);

        Optional<Double> any = Optional.ofNullable(chances.get("missing"));
        assertTrue(any.isEmpty());
    }
}
