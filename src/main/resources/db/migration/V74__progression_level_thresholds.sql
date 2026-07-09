-- ValoCase backend - align stored level with the total-XP threshold table.
--
-- Total XP is the source of truth; level is derived from it. Existing accounts
-- carried level/current_level_xp from the previous flat-20-XP model, so recompute
-- both from the untouched total_xp using the current thresholds (level capped 15).
-- Non-destructive: only level and current_level_xp are recomputed; total_xp,
-- wallet, inventory, skins, case history, battle and account data are untouched.

UPDATE accounts SET
    level = CASE
        WHEN total_xp >= 1350 THEN 15
        WHEN total_xp >= 1250 THEN 14
        WHEN total_xp >= 1155 THEN 13
        WHEN total_xp >= 1050 THEN 12
        WHEN total_xp >= 945  THEN 11
        WHEN total_xp >= 860  THEN 10
        WHEN total_xp >= 775  THEN 9
        WHEN total_xp >= 610  THEN 8
        WHEN total_xp >= 465  THEN 7
        WHEN total_xp >= 350  THEN 6
        WHEN total_xp >= 250  THEN 5
        WHEN total_xp >= 160  THEN 4
        WHEN total_xp >= 95   THEN 3
        WHEN total_xp >= 40   THEN 2
        ELSE 1
    END,
    current_level_xp = total_xp - CASE
        WHEN total_xp >= 1350 THEN 1350
        WHEN total_xp >= 1250 THEN 1250
        WHEN total_xp >= 1155 THEN 1155
        WHEN total_xp >= 1050 THEN 1050
        WHEN total_xp >= 945  THEN 945
        WHEN total_xp >= 860  THEN 860
        WHEN total_xp >= 775  THEN 775
        WHEN total_xp >= 610  THEN 610
        WHEN total_xp >= 465  THEN 465
        WHEN total_xp >= 350  THEN 350
        WHEN total_xp >= 250  THEN 250
        WHEN total_xp >= 160  THEN 160
        WHEN total_xp >= 95   THEN 95
        WHEN total_xp >= 40   THEN 40
        ELSE 0
    END;
