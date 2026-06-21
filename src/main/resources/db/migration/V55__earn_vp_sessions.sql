-- ValoCase backend - Earn VP server-authoritative session start.
--
-- earn_vp_sessions records when an Earn VP session actually started, on the
-- server clock. The claim derives elapsed duration from started_at instead of
-- trusting any client-reported duration, so a session cannot be claimed at full
-- length immediately after it starts.
--
-- Additive only; column types/nullability match the JPA entity so
-- ddl-auto=validate passes.

CREATE TABLE earn_vp_sessions (
    id         UUID PRIMARY KEY,
    account_id UUID        NOT NULL REFERENCES accounts (id),
    started_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_earn_vp_sessions_account_id ON earn_vp_sessions (account_id);
