-- Daily reward: flat 2000 VP on a rolling 24h cooldown (no streak, no calendar-day rule).
-- State now tracks the last claim instant; claims are an unconditional audit row.

ALTER TABLE daily_reward_state ADD COLUMN last_claim_at TIMESTAMPTZ;
UPDATE daily_reward_state SET last_claim_at = last_claim_date::timestamptz;
ALTER TABLE daily_reward_state ALTER COLUMN last_claim_at SET NOT NULL;
ALTER TABLE daily_reward_state DROP COLUMN last_claim_date;
ALTER TABLE daily_reward_state DROP COLUMN current_streak;

ALTER TABLE daily_claims DROP CONSTRAINT uq_daily_claims_account_date;
ALTER TABLE daily_claims DROP COLUMN claim_date;
ALTER TABLE daily_claims DROP COLUMN streak;
