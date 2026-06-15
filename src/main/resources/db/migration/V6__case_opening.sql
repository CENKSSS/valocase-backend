-- ValoCase backend - Phase 1 case opening schema.
--
-- case_openings is an audit record of each opening. Its id is also used as the
-- referenceId on the wallet debit and the caseOpeningId on the awarded
-- inventory item.
--
-- Column types/nullability match the JPA entity so ddl-auto=validate passes.
-- inventory_item_id is a recorded reference (no FK) and is populated after the
-- inventory row is created, so it stays nullable.

CREATE TABLE case_openings (
    id                UUID PRIMARY KEY,
    account_id        UUID         NOT NULL REFERENCES accounts (id),
    case_id           VARCHAR(100) NOT NULL REFERENCES case_definitions (id),
    won_skin_id       VARCHAR(100) NOT NULL REFERENCES skins (id),
    price_paid        BIGINT       NOT NULL,
    inventory_item_id UUID,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_case_openings_account_id ON case_openings (account_id);

-- Now that case_openings exists, tie the inventory link to it. Existing
-- inventory rows all have a NULL case_opening_id, so this is safe to add.
ALTER TABLE inventory_items
    ADD CONSTRAINT fk_inventory_case_opening
        FOREIGN KEY (case_opening_id) REFERENCES case_openings (id);
