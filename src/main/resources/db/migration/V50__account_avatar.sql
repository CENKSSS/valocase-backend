-- Avatar selection: a stable avatar id chosen by the player, persisted on the
-- account and snapshotted onto lobby slots / battle participants like the name.

ALTER TABLE accounts ADD COLUMN avatar_id VARCHAR(50);
UPDATE accounts SET avatar_id = 'avatar_1' WHERE avatar_id IS NULL;

ALTER TABLE battle_lobby_slots ADD COLUMN avatar_id VARCHAR(50);
ALTER TABLE battle_participants ADD COLUMN avatar_id VARCHAR(50);
