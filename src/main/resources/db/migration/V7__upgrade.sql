-- ValoCase backend - Phase 1 upgrade schema.
--
-- upgrades is an audit record of each upgrade attempt (success or failure).
-- upgrade_inputs snapshots the consumed inventory items (id + skin + value) at
-- the moment of consumption, since the source inventory rows are deleted.
--
-- Column types/nullability match the JPA entities so ddl-auto=validate passes.
-- item_id has no FK (the inventory row is gone after consumption).

CREATE TABLE upgrades (
    id                        UUID PRIMARY KEY,
    account_id                UUID             NOT NULL REFERENCES accounts (id),
    target_skin_id            VARCHAR(100)     NOT NULL REFERENCES skins (id),
    input_count               INTEGER          NOT NULL,
    input_value               BIGINT           NOT NULL,
    target_value              BIGINT           NOT NULL,
    chance                    DOUBLE PRECISION NOT NULL,
    success                   BOOLEAN          NOT NULL,
    granted_inventory_item_id UUID,
    created_at                TIMESTAMPTZ      NOT NULL
);

CREATE INDEX idx_upgrades_account_id ON upgrades (account_id);

CREATE TABLE upgrade_inputs (
    id         UUID PRIMARY KEY,
    upgrade_id UUID         NOT NULL REFERENCES upgrades (id),
    item_id    UUID         NOT NULL,
    skin_id    VARCHAR(100) NOT NULL REFERENCES skins (id),
    vp_value   INTEGER      NOT NULL
);

CREATE INDEX idx_upgrade_inputs_upgrade_id ON upgrade_inputs (upgrade_id);
