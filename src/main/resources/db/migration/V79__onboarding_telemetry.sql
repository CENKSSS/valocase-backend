-- ValoCase backend - pre-account onboarding telemetry.
--
-- WHY THIS EXISTS
-- Accounts are created only after a player types a nickname and presses Continue.
-- Everything before that point is invisible: an install that never opened the app,
-- a player who quit at the legal notice, a player who quit at the nickname screen,
-- and a player whose nickname the CLIENT refused all produce exactly the same
-- evidence today, which is none. This table records the funnel steps that happen
-- before an account exists, so those four outcomes stop being indistinguishable.
--
-- WHAT IS DELIBERATELY NOT HERE
-- No nickname text, no guest token, no authorization header, no advertising id,
-- no email, no IP address, no device model, and no free-form JSON. The columns
-- below are the whole accepted surface; anything else a client sends is dropped
-- before it reaches this table. installation_id is a client-generated UUID with
-- no link to a person or a device identifier.
--
-- IDENTITY AND IDEMPOTENCY
-- event_id is client-generated and UNIQUE. A client that retries a failed send
-- reuses the same event_id, so a retry updates nothing and inserts nothing. The
-- unique index is the enforcement point, not an application check, so concurrent
-- duplicates on two instances still collapse to one row.
--
-- Additive only: no existing table, row or timestamp is touched. All timestamps
-- are TIMESTAMPTZ (project convention; Instant <-> timestamptz). Column types and
-- nullability match the JPA entity so ddl-auto=validate passes.

CREATE TABLE IF NOT EXISTS onboarding_events (
    id                     UUID PRIMARY KEY,
    event_id               VARCHAR(64)  NOT NULL,
    installation_id        VARCHAR(64)  NOT NULL,
    event_name             VARCHAR(40)  NOT NULL,
    client_timestamp_utc   TIMESTAMPTZ,
    received_at            TIMESTAMPTZ  NOT NULL,
    app_version            VARCHAR(20),
    platform               VARCHAR(20),
    rejection_reason       VARCHAR(30),
    network_error_category VARCHAR(30),
    http_status            INTEGER
);

-- The idempotency guarantee. Enforced by the database so two app instances
-- racing on the same retry cannot both insert.
CREATE UNIQUE INDEX IF NOT EXISTS uq_onboarding_events_event_id
    ON onboarding_events (event_id);

-- Funnel counting by day and step.
CREATE INDEX IF NOT EXISTS idx_onboarding_events_name_received
    ON onboarding_events (event_name, received_at);

-- Per-installation walk-through: which steps one install reached, in order.
CREATE INDEX IF NOT EXISTS idx_onboarding_events_installation
    ON onboarding_events (installation_id, received_at);

CREATE INDEX IF NOT EXISTS idx_onboarding_events_received
    ON onboarding_events (received_at);

-- --- Funnel view --------------------------------------------------------------
--
-- One row per Istanbul day, one column per funnel step, counting DISTINCT
-- installations rather than events. Distinct matters: a player who mistypes a
-- nickname four times sends four nickname_rejected events, and counting events
-- would imply four blocked players.
--
-- account_created and session_created come from the canonical tables, not from
-- telemetry. They are day totals, NOT per-installation joins - see the note on
-- attribution below.
--
-- Day boundary is Europe/Istanbul, matching admin_daily_players (V78).

CREATE OR REPLACE VIEW admin_onboarding_funnel AS
WITH per_day AS (
    SELECT (received_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'app_launched')             AS app_launched,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'fan_notice_shown')         AS fan_notice_shown,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'fan_notice_accepted')      AS fan_notice_accepted,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'nickname_screen_shown')    AS nickname_screen_shown,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'nickname_rejected')        AS nickname_rejected,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'nickname_confirm_clicked') AS nickname_confirm_clicked,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'registration_attempted')   AS registration_attempted,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'registration_failed')      AS registration_failed,
           COUNT(DISTINCT installation_id)
               FILTER (WHERE event_name = 'registration_succeeded')   AS registration_succeeded
    FROM onboarding_events
    GROUP BY 1
),
accounts_per_day AS (
    SELECT (created_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
           COUNT(*)                                          AS account_created
    FROM accounts
    WHERE id <> '00000000-0000-0000-0000-000000000001'
    GROUP BY 1
),
sessions_per_day AS (
    SELECT (started_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
           COUNT(DISTINCT account_id)                        AS session_created
    FROM player_sessions
    WHERE account_id <> '00000000-0000-0000-0000-000000000001'
    GROUP BY 1
)
SELECT COALESCE(p.day, a.day, s.day)          AS day,
       COALESCE(p.app_launched, 0)             AS app_launched,
       COALESCE(p.fan_notice_shown, 0)         AS fan_notice_shown,
       COALESCE(p.fan_notice_accepted, 0)      AS fan_notice_accepted,
       COALESCE(p.nickname_screen_shown, 0)    AS nickname_screen_shown,
       COALESCE(p.nickname_rejected, 0)        AS nickname_rejected,
       COALESCE(p.nickname_confirm_clicked, 0) AS nickname_confirm_clicked,
       COALESCE(p.registration_attempted, 0)   AS registration_attempted,
       COALESCE(p.registration_failed, 0)      AS registration_failed,
       COALESCE(p.registration_succeeded, 0)   AS registration_succeeded,
       COALESCE(a.account_created, 0)          AS account_created,
       COALESCE(s.session_created, 0)          AS session_created
FROM per_day p
FULL JOIN accounts_per_day a ON a.day = p.day
FULL JOIN sessions_per_day s ON s.day = COALESCE(p.day, a.day);

-- Why players were blocked at the nickname screen, by day and reason code.
-- This is the number that says whether the name rule is costing us installs.
CREATE OR REPLACE VIEW admin_onboarding_rejections AS
SELECT (received_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
       COALESCE(rejection_reason, 'UNSPECIFIED')          AS rejection_reason,
       COUNT(*)                                           AS rejection_events,
       COUNT(DISTINCT installation_id)                    AS installations
FROM onboarding_events
WHERE event_name = 'nickname_rejected'
GROUP BY 1, 2;
