-- ValoCase backend - rewarded-ad reward claims.
--
-- ad_reward_claims tracks each server-authoritative rewarded-ad grant. The unique
-- (account_id, reward_type, ad_token) prevents replaying the same completed ad.
-- granted_vp is set for EARN_VP_2X; buff_percent/consumed track the UPGRADE_PLUS_5
-- buff lifecycle (unconsumed until the next upgrade attempt consumes it).
--
-- Additive only; column types/nullability match the JPA entity so
-- ddl-auto=validate passes.

CREATE TABLE ad_reward_claims (
    id           UUID PRIMARY KEY,
    account_id   UUID         NOT NULL REFERENCES accounts (id),
    reward_type  VARCHAR(32)  NOT NULL,
    ad_token     VARCHAR(100) NOT NULL,
    granted_vp   BIGINT,
    buff_percent DOUBLE PRECISION,
    consumed     BOOLEAN      NOT NULL,
    source_ref   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    CONSTRAINT uq_ad_reward_claims_account_type_token UNIQUE (account_id, reward_type, ad_token)
);

CREATE INDEX idx_ad_reward_claims_account_type_created
    ON ad_reward_claims (account_id, reward_type, created_at);
