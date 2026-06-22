-- ============================================================================
-- V59 - Melee rarity
-- ============================================================================
-- Melee skins move from the shared 'Exclusive' rarity to their own 'Melee'
-- rarity. Scoped to weapon = 'Melee' so non-melee Exclusive skins are untouched.
-- Catalog table only; no player-owned tables are referenced.
-- ============================================================================

UPDATE skins SET rarity = 'Melee' WHERE weapon = 'Melee';
