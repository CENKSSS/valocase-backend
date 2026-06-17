-- ValoCase backend - player level / XP progression.
--
-- Server is the source of truth for level, XP and case-category unlocks.
-- Fields live on the existing accounts table (one progression record per player).
-- Existing accounts migrate safely via non-null defaults: level 1, 0 XP.

ALTER TABLE accounts
    ADD COLUMN level            INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN current_level_xp INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN total_xp         BIGINT  NOT NULL DEFAULT 0;
