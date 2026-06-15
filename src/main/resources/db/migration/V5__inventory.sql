-- ValoCase backend - Phase 1 inventory schema.
--
-- Per-instance inventory: one row per owned skin instance, NO quantity column.
-- The same skin_id may appear in multiple rows for the same account.
-- Column types/nullability match the JPA entity so ddl-auto=validate passes.
--
-- case_opening_id has no FK yet (case opening is a later step); it is a plain
-- nullable UUID for now.

CREATE TABLE inventory_items (
    id              UUID PRIMARY KEY,
    account_id      UUID         NOT NULL REFERENCES accounts (id),
    skin_id         VARCHAR(100) NOT NULL REFERENCES skins (id),
    source          VARCHAR(50)  NOT NULL,
    acquired_at     TIMESTAMPTZ  NOT NULL,
    case_opening_id UUID
);

CREATE INDEX idx_inventory_account_id ON inventory_items (account_id);
