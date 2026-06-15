-- ValoCase backend - Phase 1 account + wallet schema.
--
-- Tables: accounts, wallets, wallet_transactions.
-- All primary keys are generated UUIDs (assigned by Hibernate on insert).
-- Column types/nullability match the JPA entity mappings so that
-- ddl-auto=validate passes against this Flyway-owned schema.
-- Instant fields map to timestamptz.

CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    guest_token  UUID         NOT NULL UNIQUE,
    display_name VARCHAR(100),
    status       VARCHAR(30)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    last_seen_at TIMESTAMPTZ  NOT NULL
);

CREATE TABLE wallets (
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL UNIQUE REFERENCES accounts (id),
    vp_balance BIGINT      NOT NULL,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE wallet_transactions (
    id            UUID PRIMARY KEY,
    wallet_id     UUID        NOT NULL REFERENCES wallets (id),
    delta         BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    reason        VARCHAR(50) NOT NULL,
    reference_id  UUID,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_wallet_tx_wallet_id ON wallet_transactions (wallet_id);
