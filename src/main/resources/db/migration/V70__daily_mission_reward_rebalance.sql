-- Rebalance daily mission VP rewards to the v2 economy values.
-- mission_definitions is the backend source of truth: getMissions reads its
-- reward_vp, and a player_missions row snapshots it when progress first starts.
-- Requirements, names, event types, periods and cooldown logic are unchanged;
-- only reward_vp is rebalanced. Reward values are set by code so the statement
-- is authoritative and idempotent.

UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'OPEN_3_CASES';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'SELL_2_SKINS';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'UPGRADE_1_SUCCESS';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'EARN_3000_VP';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'SELL_3000_VP';
UPDATE mission_definitions SET reward_vp = 400  WHERE code = 'WIN_2_UPGRADES';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'PLAY_2_BATTLES';
UPDATE mission_definitions SET reward_vp = 1500 WHERE code = 'OPEN_10_CASES';
UPDATE mission_definitions SET reward_vp = 500  WHERE code = 'WIN_1_BATTLE';

-- Re-snapshot the reward onto in-flight progress so a claim credits the new
-- value. Already-CLAIMED rows are historical audit (reward granted) and are
-- left untouched. Claim logic itself is unchanged.
UPDATE player_missions pm
SET reward_vp = md.reward_vp
FROM mission_definitions md
WHERE pm.mission_id = md.id
  AND pm.status <> 'CLAIMED';
