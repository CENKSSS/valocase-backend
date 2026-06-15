-- ValoCase backend - daily rewards + missions schema.
--
-- daily_reward_state holds per-account streak state (one row per account).
-- daily_claims is the per-day claim audit (unique per account per UTC date).
-- mission_definitions are seeded below (ONE_TIME for v1).
-- player_missions is per-account progress (unique per account+mission+period).
--
-- Column types/nullability match the JPA entities so ddl-auto=validate passes.

CREATE TABLE daily_reward_state (
    account_id      UUID PRIMARY KEY REFERENCES accounts (id),
    last_claim_date DATE        NOT NULL,
    current_streak  INTEGER     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE daily_claims (
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES accounts (id),
    claim_date DATE        NOT NULL,
    streak     INTEGER     NOT NULL,
    reward_vp  BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_daily_claims_account_date UNIQUE (account_id, claim_date)
);

CREATE INDEX idx_daily_claims_account_id ON daily_claims (account_id);

CREATE TABLE mission_definitions (
    id           UUID PRIMARY KEY,
    code         VARCHAR(50)  NOT NULL UNIQUE,
    title        VARCHAR(150) NOT NULL,
    description  VARCHAR(255),
    event_type   VARCHAR(50)  NOT NULL,
    target_count INTEGER      NOT NULL,
    reward_vp    BIGINT       NOT NULL,
    period       VARCHAR(20)  NOT NULL,
    active       BOOLEAN      NOT NULL,
    sort_order   INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE TABLE player_missions (
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    mission_id   UUID        NOT NULL REFERENCES mission_definitions (id),
    period_key   VARCHAR(40) NOT NULL,
    progress     INTEGER     NOT NULL,
    status       VARCHAR(20) NOT NULL,
    completed_at TIMESTAMPTZ,
    claimed_at   TIMESTAMPTZ,
    reward_vp    BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_player_missions UNIQUE (account_id, mission_id, period_key)
);

CREATE INDEX idx_player_missions_account_id ON player_missions (account_id);

-- v1 mission definitions (ONE_TIME).
INSERT INTO mission_definitions (id, code, title, description, event_type, target_count, reward_vp, period, active, sort_order, created_at) VALUES
    (gen_random_uuid(), 'OPEN_3_CASES',      'Open 3 Cases',   'Open 3 cases.',               'CASE_OPENED',       3,  500,  'ONE_TIME', TRUE, 1, now()),
    (gen_random_uuid(), 'SELL_2_SKINS',      'Sell 2 Skins',   'Sell 2 skins.',               'SKIN_SOLD',         2,  300,  'ONE_TIME', TRUE, 2, now()),
    (gen_random_uuid(), 'WIN_1_BATTLE',      'Win 1 Battle',   'Win 1 case battle.',          'BATTLE_WON',        1,  1000, 'ONE_TIME', TRUE, 3, now()),
    (gen_random_uuid(), 'UPGRADE_1_SUCCESS', 'Upgrade a Skin', 'Successfully upgrade 1 skin.', 'UPGRADE_SUCCEEDED', 1,  750,  'ONE_TIME', TRUE, 4, now()),
    (gen_random_uuid(), 'OPEN_10_CASES',     'Open 10 Cases',  'Open 10 cases.',              'CASE_OPENED',       10, 2000, 'ONE_TIME', TRUE, 5, now());
