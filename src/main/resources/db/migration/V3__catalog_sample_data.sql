-- ============================================================================
-- DEV / SAMPLE DATA ONLY  --  NOT REAL CATALOG CONTENT
-- ============================================================================
-- This migration inserts a tiny placeholder catalog so the read endpoints can
-- be exercised in a browser/Postman before real Unity catalog data is loaded.
--
-- The IDs below are obviously-fake "sample_*" IDs so they will NOT collide with
-- real Unity stable skinId / caseId values. Remove this migration (and clean
-- these rows) before loading the real catalog / before production.
-- ============================================================================

INSERT INTO skins (id, display_name, weapon, rarity, vp_value, image_ref, active) VALUES
    ('sample_skin_phantom_neon',  'Sample Neon Phantom',  'Phantom',  'PREMIUM',  1275, 'skins/sample_phantom_neon.png',  TRUE),
    ('sample_skin_vandal_dragon', 'Sample Dragon Vandal', 'Vandal',   'EXCLUSIVE',2675, 'skins/sample_vandal_dragon.png', TRUE),
    ('sample_skin_classic_basic', 'Sample Basic Classic', 'Classic',  'SELECT',   875,  'skins/sample_classic_basic.png', TRUE);

INSERT INTO case_definitions (id, display_name, price_vp, image_ref, active) VALUES
    ('sample_case_starter', 'Sample Starter Case', 500, 'cases/sample_starter.png', TRUE);

INSERT INTO case_entries (id, case_id, skin_id, weight) VALUES
    (gen_random_uuid(), 'sample_case_starter', 'sample_skin_classic_basic', 70),
    (gen_random_uuid(), 'sample_case_starter', 'sample_skin_phantom_neon',  25),
    (gen_random_uuid(), 'sample_case_starter', 'sample_skin_vandal_dragon', 5);
