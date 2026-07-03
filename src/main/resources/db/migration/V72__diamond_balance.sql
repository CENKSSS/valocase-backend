-- ValoCase backend - Diamond currency balance.
--
-- Adds a server-authoritative diamond balance to each wallet. Additive and
-- backward compatible: existing rows default to 0. Matches the JPA entity so
-- ddl-auto=validate passes.

ALTER TABLE wallets ADD COLUMN diamond_balance BIGINT NOT NULL DEFAULT 0;
