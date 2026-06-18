-- ValoCase backend - public online battle lobbies.
--
-- battle_lobbies is the mutable header of a public lobby while it fills up
-- (real players join, the creator adds bots). battle_lobby_slots holds one row
-- per slot (slot 0 is always the creator). When every slot is filled the lobby
-- resolves using the existing battle rules and the immutable outcome is written
-- into the existing battles/battle_participants/battle_rolls tables; the lobby
-- then points at that battle via result_battle_id.
--
-- Column types/nullability match the JPA entities so ddl-auto=validate passes.

CREATE TABLE battle_lobbies (
    id                 UUID PRIMARY KEY,
    creator_account_id UUID         NOT NULL REFERENCES accounts (id),
    case_id            VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    rounds             INTEGER      NOT NULL,
    max_slots          INTEGER      NOT NULL,
    entry_cost         BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    ready_at           TIMESTAMPTZ,
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    winner_slot_index  INTEGER,
    result_battle_id   UUID REFERENCES battles (id),
    version            BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_battle_lobbies_status ON battle_lobbies (status);

CREATE TABLE battle_lobby_slots (
    id           UUID PRIMARY KEY,
    lobby_id     UUID         NOT NULL REFERENCES battle_lobbies (id),
    slot_index   INTEGER      NOT NULL,
    slot_type    VARCHAR(10)  NOT NULL,
    account_id   UUID         REFERENCES accounts (id),
    display_name VARCHAR(100),
    is_creator   BOOLEAN      NOT NULL DEFAULT FALSE,
    charged_vp   BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_battle_lobby_slots_index UNIQUE (lobby_id, slot_index),
    -- A real player can occupy at most one slot per lobby. NULL account_ids
    -- (empty/bot slots) are distinct in Postgres, so this does not collide.
    CONSTRAINT uq_battle_lobby_slots_account UNIQUE (lobby_id, account_id)
);

CREATE INDEX idx_battle_lobby_slots_lobby ON battle_lobby_slots (lobby_id);
