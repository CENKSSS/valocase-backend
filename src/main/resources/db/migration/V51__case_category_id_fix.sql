-- ============================================================================
-- V51 - Fix case-category level-lock bypass by renaming misnamed case IDs.
-- ============================================================================
-- CaseCategory.fromCaseId derives the locked category from the id prefix before
-- the first underscore, so the category must be the first segment. The ids
-- protocol_melee / radiant_melee / protocol_bulldog resolved to no category and
-- bypassed the level lock. Re-point the drop pools to correctly prefixed ids and
-- retire the old ids. Old definition rows are kept inactive (not deleted) to
-- preserve foreign keys from case_openings history.
-- ============================================================================

INSERT INTO case_definitions (id, display_name, price_vp, image_ref, active)
SELECT 'melee_protocol', display_name, price_vp, image_ref, active
FROM case_definitions WHERE id = 'protocol_melee'
ON CONFLICT (id) DO NOTHING;

INSERT INTO case_definitions (id, display_name, price_vp, image_ref, active)
SELECT 'melee_radiant', display_name, price_vp, image_ref, active
FROM case_definitions WHERE id = 'radiant_melee'
ON CONFLICT (id) DO NOTHING;

INSERT INTO case_definitions (id, display_name, price_vp, image_ref, active)
SELECT 'bulldog_protocol', display_name, price_vp, image_ref, active
FROM case_definitions WHERE id = 'protocol_bulldog'
ON CONFLICT (id) DO NOTHING;

UPDATE case_entries SET case_id = 'melee_protocol' WHERE case_id = 'protocol_melee';
UPDATE case_entries SET case_id = 'melee_radiant' WHERE case_id = 'radiant_melee';
UPDATE case_entries SET case_id = 'bulldog_protocol' WHERE case_id = 'protocol_bulldog';

UPDATE case_definitions SET active = FALSE
WHERE id IN ('protocol_melee', 'radiant_melee', 'protocol_bulldog');
