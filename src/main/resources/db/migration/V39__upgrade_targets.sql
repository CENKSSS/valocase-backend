-- ValoCase backend - multi-target upgrade support.
--
-- upgrade_targets snapshots each selected target skin (id + value) for an
-- upgrade attempt. upgrades.target_skin_id / granted_inventory_item_id keep
-- holding the first target for backward compatibility.

CREATE TABLE upgrade_targets (
    id         UUID PRIMARY KEY,
    upgrade_id UUID         NOT NULL REFERENCES upgrades (id),
    skin_id    VARCHAR(100) NOT NULL REFERENCES skins (id),
    vp_value   INTEGER      NOT NULL,
    granted_inventory_item_id UUID
);

CREATE INDEX idx_upgrade_targets_upgrade_id ON upgrade_targets (upgrade_id);
