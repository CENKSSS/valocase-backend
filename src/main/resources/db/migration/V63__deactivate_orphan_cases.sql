-- ============================================================================
-- V63 - Deactivate orphan/legacy cases
-- ============================================================================
-- Only the 20 Unity CaseManager-authored cases (V62) should stay active. Every
-- other case_definitions row is a legacy/orphan and is set inactive. Rows are
-- preserved (no deletes) so historical case_openings keep their references.
-- Catalog table only; no player-owned tables are referenced.
-- ============================================================================

UPDATE case_definitions
SET active = FALSE
WHERE id NOT IN (
        'bulldog_arcane',
        'bulldog_basic',
        'bulldog_protocol',
        'bulldog_radiant',
        'classic_arcane',
        'classic_basic',
        'classic_protocol',
        'classic_radiant',
        'ghost_arcane',
        'ghost_basic',
        'ghost_protocol',
        'ghost_radiant',
        'melee_arcane',
        'melee_basic',
        'melee_protocol',
        'melee_radiant',
        'vandal_arcane',
        'vandal_basic',
        'vandal_protocol',
        'vandal_radiant'
);
