-- ValoCase backend - Earn VP session claim schema.
--
-- earn_vp_claims is the per-session audit of server-granted Earn VP. The reward
-- is computed server-side; clientSessionId is unique per account so a resubmitted
-- session never grants twice.
--
-- Column types/nullability match the JPA entity so ddl-auto=validate passes.

CREATE TABLE earn_vp_claims (
    id                 UUID PRIMARY KEY,
    account_id         UUID         NOT NULL REFERENCES accounts (id),
    client_session_id  VARCHAR(100) NOT NULL,
    tap_count_accepted INTEGER      NOT NULL,
    vp_granted         BIGINT       NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_earn_vp_claims_account_session UNIQUE (account_id, client_session_id)
);

CREATE INDEX idx_earn_vp_claims_account_id ON earn_vp_claims (account_id);
