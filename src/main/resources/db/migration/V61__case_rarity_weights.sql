-- ============================================================================
-- V61 - Case rarity weights (rarity-first roll odds)
-- ============================================================================
-- Persists the Unity CaseManager-authored rarityWeights from
-- src/main/resources/catalog/cases.json so production (migration-driven) rolls
-- rarity-first instead of from flat per-skin pool composition. Catalog table
-- only; no player-owned tables are referenced. Values are generated directly
-- from cases.json (not hand-authored).
--
-- Cases present in cases.json with authored weights: vandal_basic, vandal_tactical, vandal_elite, vandal_protocol, vandal_radiant, vandal_arcane, melee_case
-- Cases NOT in cases.json (no weights seeded; roll safely falls back to flat
-- per-skin until they are added to cases.json): melee_basic, melee_protocol,
-- melee_radiant, melee_arcane.
-- ============================================================================

CREATE TABLE case_rarity_weights (
    id      UUID         PRIMARY KEY,
    case_id VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    rarity  VARCHAR(50)  NOT NULL,
    weight  DOUBLE PRECISION NOT NULL,
    CONSTRAINT uq_case_rarity_weights_case_rarity UNIQUE (case_id, rarity)
);

CREATE INDEX idx_case_rarity_weights_case_id ON case_rarity_weights (case_id);

INSERT INTO case_rarity_weights (id, case_id, rarity, weight) VALUES
    (gen_random_uuid(), 'vandal_basic', 'Select', 65),
    (gen_random_uuid(), 'vandal_basic', 'Deluxe', 15),
    (gen_random_uuid(), 'vandal_basic', 'Premium', 10),
    (gen_random_uuid(), 'vandal_basic', 'Exclusive', 7),
    (gen_random_uuid(), 'vandal_basic', 'Ultra', 3),
    (gen_random_uuid(), 'vandal_tactical', 'Select', 40),
    (gen_random_uuid(), 'vandal_tactical', 'Deluxe', 30),
    (gen_random_uuid(), 'vandal_tactical', 'Premium', 15),
    (gen_random_uuid(), 'vandal_tactical', 'Exclusive', 10),
    (gen_random_uuid(), 'vandal_tactical', 'Ultra', 5),
    (gen_random_uuid(), 'vandal_elite', 'Select', 20),
    (gen_random_uuid(), 'vandal_elite', 'Deluxe', 35),
    (gen_random_uuid(), 'vandal_elite', 'Premium', 25),
    (gen_random_uuid(), 'vandal_elite', 'Exclusive', 15),
    (gen_random_uuid(), 'vandal_elite', 'Ultra', 5),
    (gen_random_uuid(), 'vandal_protocol', 'Select', 10),
    (gen_random_uuid(), 'vandal_protocol', 'Deluxe', 20),
    (gen_random_uuid(), 'vandal_protocol', 'Premium', 35),
    (gen_random_uuid(), 'vandal_protocol', 'Exclusive', 25),
    (gen_random_uuid(), 'vandal_protocol', 'Ultra', 10),
    (gen_random_uuid(), 'vandal_radiant', 'Select', 5),
    (gen_random_uuid(), 'vandal_radiant', 'Deluxe', 10),
    (gen_random_uuid(), 'vandal_radiant', 'Premium', 25),
    (gen_random_uuid(), 'vandal_radiant', 'Exclusive', 35),
    (gen_random_uuid(), 'vandal_radiant', 'Ultra', 25),
    (gen_random_uuid(), 'vandal_arcane', 'Select', 2),
    (gen_random_uuid(), 'vandal_arcane', 'Deluxe', 5),
    (gen_random_uuid(), 'vandal_arcane', 'Premium', 18),
    (gen_random_uuid(), 'vandal_arcane', 'Exclusive', 35),
    (gen_random_uuid(), 'vandal_arcane', 'Ultra', 40),
    (gen_random_uuid(), 'melee_case', 'Select', 10),
    (gen_random_uuid(), 'melee_case', 'Deluxe', 20),
    (gen_random_uuid(), 'melee_case', 'Premium', 35),
    (gen_random_uuid(), 'melee_case', 'Exclusive', 25),
    (gen_random_uuid(), 'melee_case', 'Ultra', 10);
