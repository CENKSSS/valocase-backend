-- ValoCase backend - the installation -> account link, recorded at registration.
--
-- WHY THIS EXISTS
-- The link already existed, but only one table over and only from the first
-- authenticated request: player_sessions.installation_id (V76) is populated for
-- every session a real client opens. What it cannot cover is the moment of
-- registration itself. An account whose very first session never arrives -- the
-- player registers and the app dies, or the process is killed between the
-- guest call and the first wallet fetch -- has no row in player_sessions and so
-- no link at all. Two such accounts exist today.
--
-- Recording the id on the account closes that window: the value is written in
-- the same transaction as the account, so an account and its installation are
-- created together or not at all.
--
-- NOT UNIQUE, AND THAT IS A PRODUCT FACT, NOT AN OVERSIGHT
-- One installation legitimately creates several accounts. Evidence from
-- production before this migration: installation 5f291722-... is linked to three
-- distinct accounts, and a second installation to two. Local save data can be
-- cleared, a device can be handed to another person, and a reinstall keeps the
-- id in PlayerPrefs. A UNIQUE constraint here would refuse those registrations
-- outright, so the index below is deliberately non-unique.
--
-- NULLABLE, AND STAYS THAT WAY
-- Every account created before this migration has no installation id and none is
-- invented for those rows. Clients older than the release that sends the field
-- -- 1.0.19 and 1.0.21, both live in the store right now -- keep registering
-- exactly as before and simply leave the column null.
--
-- Additive only: no existing table, row or timestamp is modified. The column
-- type is UUID to match player_sessions.installation_id, so the two join without
-- a cast. Column type/nullability match the JPA entity so ddl-auto=validate
-- passes.

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS installation_id UUID;

-- Analytics joins only, and only ever for rows that have a value. The partial
-- predicate keeps the index off the 30 historical accounts that will never carry
-- one, and off every future registration by an older client.
CREATE INDEX IF NOT EXISTS idx_accounts_installation_id
    ON accounts (installation_id)
    WHERE installation_id IS NOT NULL;

-- --- Installation journey view ------------------------------------------------
--
-- One row per installation, joining the three places an installation leaves a
-- trace: pre-account telemetry (onboarding_events), the account it created
-- (accounts.installation_id), and the sessions it opened
-- (player_sessions.installation_id).
--
-- The cast is the reason this view exists. onboarding_events.installation_id is
-- VARCHAR(64) because that endpoint is unauthenticated and must be able to store
-- whatever a broken client sends without failing the insert; the other two
-- columns are UUID. Every query that wants the full journey would otherwise
-- repeat this guarded cast by hand and get it subtly wrong. The regex guard runs
-- inside a CASE so a non-UUID value yields NULL instead of raising 22P02.

CREATE OR REPLACE VIEW admin_installation_journey AS
WITH telemetry AS (
    SELECT CASE
               WHEN installation_id ~*
                    '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
               THEN installation_id::uuid
           END                                                    AS installation_uuid,
           installation_id                                        AS installation_raw,
           MIN(received_at)                                       AS first_event_at,
           MAX(received_at)                                       AS last_event_at,
           COUNT(*)                                               AS event_count,
           COUNT(DISTINCT event_name)                             AS distinct_steps,
           MAX(app_version)                                       AS app_version,
           MAX(platform)                                          AS platform,
           BOOL_OR(event_name = 'app_launched')                   AS reached_app_launched,
           BOOL_OR(event_name = 'fan_notice_shown')               AS reached_fan_notice,
           BOOL_OR(event_name = 'fan_notice_accepted')            AS accepted_fan_notice,
           BOOL_OR(event_name = 'nickname_screen_shown')          AS reached_nickname,
           BOOL_OR(event_name = 'registration_attempted')         AS attempted_registration,
           BOOL_OR(event_name = 'registration_succeeded')         AS succeeded_registration
    FROM onboarding_events
    GROUP BY 1, 2
),
linked_accounts AS (
    SELECT installation_id                                        AS installation_uuid,
           COUNT(*)                                               AS account_count,
           MIN(created_at)                                        AS first_account_at,
           MAX(display_name)                                      AS a_display_name
    FROM accounts
    WHERE installation_id IS NOT NULL
    GROUP BY 1
),
session_accounts AS (
    SELECT s.installation_id                                      AS installation_uuid,
           COUNT(DISTINCT s.account_id)                           AS session_account_count,
           MIN(s.started_at)                                      AS first_session_at,
           MAX(COALESCE(s.ended_at, s.last_activity_at))          AS last_session_at
    FROM player_sessions s
    WHERE s.installation_id IS NOT NULL
      AND s.account_id <> '00000000-0000-0000-0000-000000000001'
    GROUP BY 1
)
SELECT COALESCE(t.installation_uuid, la.installation_uuid, sa.installation_uuid)
                                                                  AS installation_id,
       t.installation_raw                                         AS telemetry_installation_raw,
       t.first_event_at                                           AS first_event_at,
       t.last_event_at                                            AS last_event_at,
       COALESCE(t.event_count, 0)                                 AS event_count,
       COALESCE(t.distinct_steps, 0)                              AS distinct_steps,
       t.app_version                                              AS telemetry_app_version,
       t.platform                                                 AS telemetry_platform,
       COALESCE(t.reached_app_launched, FALSE)                    AS reached_app_launched,
       COALESCE(t.reached_fan_notice, FALSE)                      AS reached_fan_notice,
       COALESCE(t.accepted_fan_notice, FALSE)                     AS accepted_fan_notice,
       COALESCE(t.reached_nickname, FALSE)                        AS reached_nickname,
       COALESCE(t.attempted_registration, FALSE)                  AS attempted_registration,
       COALESCE(t.succeeded_registration, FALSE)                  AS succeeded_registration,
       COALESCE(la.account_count, 0)                              AS accounts_registered_here,
       la.first_account_at                                        AS first_account_at,
       la.a_display_name                                          AS registered_display_name,
       COALESCE(sa.session_account_count, 0)                      AS accounts_seen_in_sessions,
       sa.first_session_at                                        AS first_session_at,
       sa.last_session_at                                         AS last_session_at
FROM telemetry t
FULL JOIN linked_accounts la ON la.installation_uuid = t.installation_uuid
FULL JOIN session_accounts sa
       ON sa.installation_uuid = COALESCE(t.installation_uuid, la.installation_uuid);
