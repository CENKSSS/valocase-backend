-- ValoCase backend - remove the level cap.
--
-- Levels used to stop at 15, so every player past 1350 total XP was pinned to
-- level 15 with current_level_xp holding the whole overflow. Levelling now
-- continues past 15 at a flat 100 XP per level; nothing new unlocks up there
-- (MELEE, the last category, already opens at 15) but the level keeps climbing.
--
-- This also removes a client-visible hazard: at the old cap the server reported
-- "XP needed for next level = 0", which a client dividing by that value could
-- not render. There is no maximum level any more, so that value is always > 0.
--
-- level and current_level_xp are only caches of total_xp (the source of truth),
-- so this recomputes both and never invents or moves XP. Accounts below 1350
-- total XP are unaffected: their thresholds did not change.

-- LEAST mirrors the clamp the service applies: a hand-edited or corrupted
-- total_xp must not produce a level that overflows the INTEGER column.
UPDATE accounts
SET level            = LEAST(15 + (total_xp - 1350) / 100, 2147483647),
    current_level_xp = (total_xp - 1350) % 100
WHERE total_xp >= 1350;
