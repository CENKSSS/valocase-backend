-- ValoCase backend - Phase 1 bot battle schema.
--
-- battles is the header (one row per battle). battle_participants holds one row
-- per participant (user at index 0, bots 1..N-1) with their total VP.
-- battle_rolls holds one row per rolled skin (participant x round), snapshotting
-- the skin and its vpValue; granted_inventory_item_id is set on each roll only
-- when the user wins (the rewards are then granted to the user).
--
-- Column types/nullability match the JPA entities so ddl-auto=validate passes.

CREATE TABLE battles (
    id                UUID PRIMARY KEY,
    account_id        UUID         NOT NULL REFERENCES accounts (id),
    case_id           VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    rounds            INTEGER      NOT NULL,
    participant_count INTEGER      NOT NULL,
    entry_cost        BIGINT       NOT NULL,
    winner_index      INTEGER      NOT NULL,
    user_won          BOOLEAN      NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_battles_account_id ON battles (account_id);

CREATE TABLE battle_participants (
    id                UUID PRIMARY KEY,
    battle_id         UUID         NOT NULL REFERENCES battles (id),
    participant_index INTEGER      NOT NULL,
    is_user           BOOLEAN      NOT NULL,
    name              VARCHAR(100),
    total_vp          BIGINT       NOT NULL,
    CONSTRAINT uq_battle_participants UNIQUE (battle_id, participant_index)
);

CREATE INDEX idx_battle_participants_battle_id ON battle_participants (battle_id);

CREATE TABLE battle_rolls (
    id                        UUID PRIMARY KEY,
    battle_id                 UUID         NOT NULL REFERENCES battles (id),
    participant_index         INTEGER      NOT NULL,
    round_number              INTEGER      NOT NULL,
    skin_id                   VARCHAR(100) NOT NULL REFERENCES skins (id),
    vp_value                  INTEGER      NOT NULL,
    granted_inventory_item_id UUID,
    CONSTRAINT uq_battle_rolls UNIQUE (battle_id, participant_index, round_number)
);

CREATE INDEX idx_battle_rolls_battle_id ON battle_rolls (battle_id);
