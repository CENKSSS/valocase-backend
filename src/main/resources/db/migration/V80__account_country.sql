-- ValoCase backend - player-selected country.
--
-- WHY THIS EXISTS
-- Nothing in the schema says where our players are. The daily and funnel views
-- can say how many registered and how many finished onboarding, but not that
-- (say) every Algerian install dies at the nickname screen while Turkish ones do
-- not. The country screen the client is gaining asks the player directly, and
-- this migration is where that answer lands.
--
-- WHAT IS STORED, AND WHAT IS NOT
-- Only the ISO-3166-1 alpha-2 code, uppercase: 'TR', not 'Türkiye', 'Turkey' or
-- 'TUR'. The name the player reads is a client-side translation of the code, so
-- storing a name would make one country several values depending on the device
-- language. There is no country_name column and none should be added.
--
-- SELF-REPORTED, NOT VERIFIED
-- This is what the player picked from a list. It is not derived from an IP
-- address, not from the store locale, not from the SIM, and nothing here
-- verifies it. A player can pick any country, and can change it later from
-- Settings. Any report built on this column is reporting a stated preference.
--
-- NULLABLE, DELIBERATELY
-- Every account that exists today was created without a country, and the client
-- in the store still cannot send one. The column is therefore nullable and NULL
-- means "never asked" — not "unknown country". No backfill runs: there is no
-- honest way to fill those rows, and guessing from an IP would put invented data
-- under a column the reports treat as a player's own answer. The column becomes
-- mandatory in the application (valocase.registration.require-country-code),
-- not in the schema, so old rows stay valid forever.
--
-- Additive only: one new nullable column on each of two tables, plus indexes and
-- views. No existing row, column or view loses anything.

ALTER TABLE accounts
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

ALTER TABLE onboarding_events
    ADD COLUMN IF NOT EXISTS country_code VARCHAR(2);

-- --- ISO allowlist, enforced by the database ----------------------------------
--
-- The same 249 officially assigned alpha-2 codes the application validates
-- against (com.cenk.valocase.common.country.CountryCodes). Two copies of one
-- list is a real maintenance cost, so it is worth saying why it is paid:
-- the application check protects the API, but only the constraint protects the
-- column from an ad-hoc UPDATE, a future code path, or an import script writing
-- 'Turkey' into it. Once that happens every country report is wrong and no
-- deploy fixes the rows already written.
--
-- The drift risk is handled rather than tolerated: AccountCountryConstraintIT
-- reads this constraint back out of pg_constraint and fails if its code set is
-- not exactly the Java set. The list itself is close to static — ISO has
-- assigned one new alpha-2 code since 2011 (SS), and renames such as Türkiye in
-- 2022 change the name, never the code. Adding a country is one migration that
-- drops and recreates these two constraints alongside the Java literal.
--
-- NULL passes both constraints: that is the migration window, and old rows.

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_accounts_country_code') THEN
        ALTER TABLE accounts ADD CONSTRAINT ck_accounts_country_code CHECK (
            country_code IS NULL OR country_code IN (
        'AD', 'AE', 'AF', 'AG', 'AI', 'AL', 'AM', 'AO', 'AQ', 'AR', 'AS', 'AT',
        'AU', 'AW', 'AX', 'AZ', 'BA', 'BB', 'BD', 'BE', 'BF', 'BG', 'BH', 'BI',
        'BJ', 'BL', 'BM', 'BN', 'BO', 'BQ', 'BR', 'BS', 'BT', 'BV', 'BW', 'BY',
        'BZ', 'CA', 'CC', 'CD', 'CF', 'CG', 'CH', 'CI', 'CK', 'CL', 'CM', 'CN',
        'CO', 'CR', 'CU', 'CV', 'CW', 'CX', 'CY', 'CZ', 'DE', 'DJ', 'DK', 'DM',
        'DO', 'DZ', 'EC', 'EE', 'EG', 'EH', 'ER', 'ES', 'ET', 'FI', 'FJ', 'FK',
        'FM', 'FO', 'FR', 'GA', 'GB', 'GD', 'GE', 'GF', 'GG', 'GH', 'GI', 'GL',
        'GM', 'GN', 'GP', 'GQ', 'GR', 'GS', 'GT', 'GU', 'GW', 'GY', 'HK', 'HM',
        'HN', 'HR', 'HT', 'HU', 'ID', 'IE', 'IL', 'IM', 'IN', 'IO', 'IQ', 'IR',
        'IS', 'IT', 'JE', 'JM', 'JO', 'JP', 'KE', 'KG', 'KH', 'KI', 'KM', 'KN',
        'KP', 'KR', 'KW', 'KY', 'KZ', 'LA', 'LB', 'LC', 'LI', 'LK', 'LR', 'LS',
        'LT', 'LU', 'LV', 'LY', 'MA', 'MC', 'MD', 'ME', 'MF', 'MG', 'MH', 'MK',
        'ML', 'MM', 'MN', 'MO', 'MP', 'MQ', 'MR', 'MS', 'MT', 'MU', 'MV', 'MW',
        'MX', 'MY', 'MZ', 'NA', 'NC', 'NE', 'NF', 'NG', 'NI', 'NL', 'NO', 'NP',
        'NR', 'NU', 'NZ', 'OM', 'PA', 'PE', 'PF', 'PG', 'PH', 'PK', 'PL', 'PM',
        'PN', 'PR', 'PS', 'PT', 'PW', 'PY', 'QA', 'RE', 'RO', 'RS', 'RU', 'RW',
        'SA', 'SB', 'SC', 'SD', 'SE', 'SG', 'SH', 'SI', 'SJ', 'SK', 'SL', 'SM',
        'SN', 'SO', 'SR', 'SS', 'ST', 'SV', 'SX', 'SY', 'SZ', 'TC', 'TD', 'TF',
        'TG', 'TH', 'TJ', 'TK', 'TL', 'TM', 'TN', 'TO', 'TR', 'TT', 'TV', 'TW',
        'TZ', 'UA', 'UG', 'UM', 'US', 'UY', 'UZ', 'VA', 'VC', 'VE', 'VG', 'VI',
        'VN', 'VU', 'WF', 'WS', 'YE', 'YT', 'ZA', 'ZM', 'ZW'));
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_onboarding_events_country_code') THEN
        ALTER TABLE onboarding_events ADD CONSTRAINT ck_onboarding_events_country_code CHECK (
            country_code IS NULL OR country_code IN (
        'AD', 'AE', 'AF', 'AG', 'AI', 'AL', 'AM', 'AO', 'AQ', 'AR', 'AS', 'AT',
        'AU', 'AW', 'AX', 'AZ', 'BA', 'BB', 'BD', 'BE', 'BF', 'BG', 'BH', 'BI',
        'BJ', 'BL', 'BM', 'BN', 'BO', 'BQ', 'BR', 'BS', 'BT', 'BV', 'BW', 'BY',
        'BZ', 'CA', 'CC', 'CD', 'CF', 'CG', 'CH', 'CI', 'CK', 'CL', 'CM', 'CN',
        'CO', 'CR', 'CU', 'CV', 'CW', 'CX', 'CY', 'CZ', 'DE', 'DJ', 'DK', 'DM',
        'DO', 'DZ', 'EC', 'EE', 'EG', 'EH', 'ER', 'ES', 'ET', 'FI', 'FJ', 'FK',
        'FM', 'FO', 'FR', 'GA', 'GB', 'GD', 'GE', 'GF', 'GG', 'GH', 'GI', 'GL',
        'GM', 'GN', 'GP', 'GQ', 'GR', 'GS', 'GT', 'GU', 'GW', 'GY', 'HK', 'HM',
        'HN', 'HR', 'HT', 'HU', 'ID', 'IE', 'IL', 'IM', 'IN', 'IO', 'IQ', 'IR',
        'IS', 'IT', 'JE', 'JM', 'JO', 'JP', 'KE', 'KG', 'KH', 'KI', 'KM', 'KN',
        'KP', 'KR', 'KW', 'KY', 'KZ', 'LA', 'LB', 'LC', 'LI', 'LK', 'LR', 'LS',
        'LT', 'LU', 'LV', 'LY', 'MA', 'MC', 'MD', 'ME', 'MF', 'MG', 'MH', 'MK',
        'ML', 'MM', 'MN', 'MO', 'MP', 'MQ', 'MR', 'MS', 'MT', 'MU', 'MV', 'MW',
        'MX', 'MY', 'MZ', 'NA', 'NC', 'NE', 'NF', 'NG', 'NI', 'NL', 'NO', 'NP',
        'NR', 'NU', 'NZ', 'OM', 'PA', 'PE', 'PF', 'PG', 'PH', 'PK', 'PL', 'PM',
        'PN', 'PR', 'PS', 'PT', 'PW', 'PY', 'QA', 'RE', 'RO', 'RS', 'RU', 'RW',
        'SA', 'SB', 'SC', 'SD', 'SE', 'SG', 'SH', 'SI', 'SJ', 'SK', 'SL', 'SM',
        'SN', 'SO', 'SR', 'SS', 'ST', 'SV', 'SX', 'SY', 'SZ', 'TC', 'TD', 'TF',
        'TG', 'TH', 'TJ', 'TK', 'TL', 'TM', 'TN', 'TO', 'TR', 'TT', 'TV', 'TW',
        'TZ', 'UA', 'UG', 'UM', 'US', 'UY', 'UZ', 'VA', 'VC', 'VE', 'VG', 'VI',
        'VN', 'VU', 'WF', 'WS', 'YE', 'YT', 'ZA', 'ZM', 'ZW'));
    END IF;
END $$;

-- Country roll-ups scan by country and by registration day.
CREATE INDEX IF NOT EXISTS idx_accounts_country_created
    ON accounts (country_code, created_at);

-- Resolving an installation's selected country when walking its funnel.
CREATE INDEX IF NOT EXISTS idx_onboarding_events_country
    ON onboarding_events (country_code, event_name, received_at);

-- --- Admin player detail ------------------------------------------------------
--
-- Unchanged from V76 except for the country_code column appended at the end.
-- CREATE OR REPLACE VIEW cannot reorder or remove columns, so the addition has
-- to go last and the rest has to be restated verbatim.

CREATE OR REPLACE VIEW admin_user_analytics AS
WITH session_agg AS (
    SELECT account_id,
           COUNT(*)                                            AS session_count,
           SUM(GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(ended_at, last_activity_at) - started_at)))) AS total_play_seconds
    FROM player_sessions
    GROUP BY account_id
),
last_session AS (
    SELECT DISTINCT ON (account_id)
           account_id, started_at, last_activity_at, ended_at
    FROM player_sessions
    ORDER BY account_id, started_at DESC
),
wallet_agg AS (
    SELECT w.account_id,
           SUM(CASE WHEN t.delta > 0 THEN t.delta ELSE 0 END)   AS total_vp_earned,
           SUM(CASE WHEN t.delta < 0 THEN -t.delta ELSE 0 END)  AS total_vp_spent,
           COUNT(*) FILTER (WHERE t.delta > 0)                  AS vp_earn_tx_count,
           COUNT(*) FILTER (WHERE t.delta < 0)                  AS vp_spend_tx_count,
           COUNT(*) FILTER (WHERE t.reason IN
               ('DAILY_REWARD', 'MISSION_REWARD', 'EARN_VP', 'AD_REWARD_MARKET_VP')) AS rewards_claimed_count,
           COUNT(*) FILTER (WHERE t.reason = 'INVENTORY_SELL')  AS sell_operation_count,
           MAX(t.created_at) FILTER (WHERE t.reason = 'INVENTORY_SELL') AS last_sell_at
    FROM wallet_transactions t
    JOIN wallets w ON w.id = t.wallet_id
    GROUP BY w.account_id
),
battle_agg AS (
    SELECT account_id,
           SUM(joined)    AS battle_joined_count,
           SUM(completed) AS battle_completed_count,
           SUM(wins)      AS battle_win_count,
           MAX(last_at)   AS last_battle_at
    FROM (
        SELECT s.account_id,
               COUNT(*) AS joined,
               COUNT(*) FILTER (WHERE l.status = 'COMPLETED') AS completed,
               COUNT(*) FILTER (WHERE l.status = 'COMPLETED'
                                  AND s.slot_index = l.winner_slot_index) AS wins,
               MAX(COALESCE(l.completed_at, l.created_at)) AS last_at
        FROM battle_lobby_slots s
        JOIN battle_lobbies l ON l.id = s.lobby_id
        WHERE s.slot_type = 'REAL' AND s.account_id IS NOT NULL
        GROUP BY s.account_id
        UNION ALL
        SELECT b.account_id,
               COUNT(*),
               COUNT(*),
               COUNT(*) FILTER (WHERE b.user_won),
               MAX(b.created_at)
        FROM battles b
        WHERE NOT EXISTS (SELECT 1 FROM battle_lobbies bl WHERE bl.result_battle_id = b.id)
        GROUP BY b.account_id
    ) src
    GROUP BY account_id
),
upgrade_agg AS (
    SELECT account_id,
           COUNT(*)                            AS upgrade_attempt_count,
           COUNT(*) FILTER (WHERE success)     AS upgrade_success_count,
           COUNT(*) FILTER (WHERE NOT success) AS upgrade_failure_count,
           MAX(created_at)                     AS last_upgrade_at
    FROM upgrades
    GROUP BY account_id
),
case_agg AS (
    SELECT account_id,
           COUNT(*)        AS case_open_count,
           MAX(created_at) AS last_case_open_at
    FROM case_openings
    GROUP BY account_id
),
sold_agg AS (
    SELECT account_id,
           COALESCE(SUM(quantity), 0) AS skins_sold_count
    FROM player_activity_events
    WHERE event_type = 'SKINS_SOLD'
    GROUP BY account_id
),
client_session AS (
    SELECT DISTINCT ON (account_id)
           account_id, id AS session_id, started_at, ended_at, last_heartbeat_at,
           explicit_ended_at, lifecycle_state, app_version, platform,
           installation_id, client_session_id
    FROM player_sessions
    WHERE client_session_id IS NOT NULL
    ORDER BY account_id, started_at DESC
),
seg AS (
    SELECT ps.account_id, g.session_id, g.started_at, g.ended_at, g.end_reason,
           GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at))) AS active_seconds
    FROM player_session_segments g
    JOIN player_sessions ps ON ps.id = g.session_id
    WHERE ps.client_session_id IS NOT NULL
),
seg_agg AS (
    SELECT account_id, SUM(active_seconds) AS total_foreground_seconds
    FROM seg GROUP BY account_id
),
seg_last AS (
    SELECT s.account_id, SUM(s.active_seconds) AS last_session_foreground_seconds
    FROM seg s JOIN client_session cs ON cs.session_id = s.session_id
    GROUP BY s.account_id
),
bg AS (
    SELECT account_id, SUM(gap_seconds) AS total_background_seconds
    FROM (
        SELECT ps.account_id,
               GREATEST(0, EXTRACT(EPOCH FROM
                   (LEAD(g.started_at) OVER (PARTITION BY g.session_id ORDER BY g.started_at)
                    - g.ended_at))) AS gap_seconds
        FROM player_session_segments g
        JOIN player_sessions ps ON ps.id = g.session_id
        WHERE ps.client_session_id IS NOT NULL
    ) gaps
    GROUP BY account_id
),
pause_resume AS (
    SELECT ps.account_id,
           MAX(g.ended_at) FILTER (WHERE g.end_reason = 'PAUSE') AS last_pause_at,
           MAX(g.started_at) FILTER (WHERE g.started_at > ps.started_at) AS last_resume_at,
           MAX(ps.last_heartbeat_at) AS last_heartbeat_at
    FROM player_sessions ps
    LEFT JOIN player_session_segments g ON g.session_id = ps.id
    WHERE ps.client_session_id IS NOT NULL
    GROUP BY ps.account_id
)
SELECT a.id                                          AS user_id,
       a.display_name                                AS username,
       a.status                                      AS account_status,
       a.level                                       AS level,
       COALESCE(w.vp_balance, 0)                     AS current_vp_balance,
       COALESCE(w.diamond_balance, 0)                AS current_diamond_balance,
       a.created_at                                  AS first_seen_at,
       COALESCE(ls.started_at, a.created_at)         AS last_login_at,
       a.last_seen_at                                AS last_activity_at,
       ls.started_at                                 AS estimated_last_session_start,
       CASE WHEN ls.ended_at IS NOT NULL THEN ls.ended_at
            WHEN ls.last_activity_at < now() - INTERVAL '5 minutes' THEN ls.last_activity_at
       END                                           AS estimated_last_session_end,
       ROUND(GREATEST(0, EXTRACT(EPOCH FROM
           (COALESCE(ls.ended_at, ls.last_activity_at) - ls.started_at))) / 60.0, 1)
                                                     AS estimated_last_session_minutes,
       ROUND(COALESCE(sa.total_play_seconds, 0) / 60.0, 1) AS estimated_total_play_minutes,
       COALESCE(sa.session_count, 0)                 AS estimated_session_count,
       a.last_seen_at >= now() - INTERVAL '5 minutes' AS is_currently_online,
       COALESCE(wa.total_vp_earned, 0)               AS total_vp_earned,
       COALESCE(wa.total_vp_spent, 0)                AS total_vp_spent,
       COALESCE(wa.vp_earn_tx_count, 0)              AS vp_earn_tx_count,
       COALESCE(wa.vp_spend_tx_count, 0)             AS vp_spend_tx_count,
       COALESCE(wa.rewards_claimed_count, 0)         AS rewards_claimed_count,
       COALESCE(ba.battle_joined_count, 0)           AS battle_count,
       COALESCE(ba.battle_completed_count, 0)        AS battle_completed_count,
       COALESCE(ba.battle_win_count, 0)              AS battle_win_count,
       COALESCE(ba.battle_completed_count, 0)
           - COALESCE(ba.battle_win_count, 0)        AS battle_loss_count,
       COALESCE(ua.upgrade_attempt_count, 0)         AS upgrade_attempt_count,
       COALESCE(ua.upgrade_success_count, 0)         AS upgrade_success_count,
       COALESCE(ua.upgrade_failure_count, 0)         AS upgrade_failure_count,
       COALESCE(ca.case_open_count, 0)               AS case_open_count,
       COALESCE(so.skins_sold_count, 0)              AS skins_sold_count,
       COALESCE(wa.sell_operation_count, 0)          AS sell_operation_count,
       ba.last_battle_at                             AS last_battle_at,
       ua.last_upgrade_at                            AS last_upgrade_at,
       ca.last_case_open_at                          AS last_case_open_at,
       GREATEST(ca.last_case_open_at, ba.last_battle_at,
                ua.last_upgrade_at, wa.last_sell_at) AS last_gameplay_at,
       cs.started_at                                 AS last_app_session_start,
       cs.ended_at                                   AS last_app_session_end,
       pr.last_heartbeat_at                          AS last_heartbeat_at,
       pr.last_pause_at                              AS last_pause_at,
       pr.last_resume_at                             AS last_resume_at,
       cs.explicit_ended_at                          AS last_explicit_end_at,
       ROUND(COALESCE(sl.last_session_foreground_seconds, 0) / 60.0, 1)
                                                     AS last_session_active_minutes,
       ROUND(COALESCE(sga.total_foreground_seconds, 0) / 60.0, 1)
                                                     AS total_active_foreground_minutes,
       ROUND(COALESCE(bg.total_background_seconds, 0) / 60.0, 1)
                                                     AS total_background_minutes,
       CASE WHEN cs.explicit_ended_at IS NOT NULL THEN 'EXPLICIT'
            WHEN cs.ended_at IS NOT NULL THEN 'TIMEOUT'
            WHEN cs.session_id IS NOT NULL THEN 'OPEN'
       END                                           AS last_session_close_type,
       cs.lifecycle_state                            AS current_lifecycle_state,
       (cs.ended_at IS NULL AND cs.lifecycle_state = 'FOREGROUND'
            AND cs.last_heartbeat_at >= now() - INTERVAL '90 seconds') AS is_client_online,
       cs.app_version                                AS current_app_version,
       cs.platform                                   AS current_platform,
       cs.installation_id                            AS current_installation_id,
       cs.client_session_id                          AS current_client_session_id,
       a.country_code                                AS country_code
FROM accounts a
LEFT JOIN wallets w      ON w.account_id  = a.id
LEFT JOIN session_agg sa ON sa.account_id = a.id
LEFT JOIN last_session ls ON ls.account_id = a.id
LEFT JOIN wallet_agg wa  ON wa.account_id = a.id
LEFT JOIN battle_agg ba  ON ba.account_id = a.id
LEFT JOIN upgrade_agg ua ON ua.account_id = a.id
LEFT JOIN case_agg ca    ON ca.account_id = a.id
LEFT JOIN sold_agg so    ON so.account_id = a.id
LEFT JOIN client_session cs ON cs.account_id = a.id
LEFT JOIN seg_agg sga    ON sga.account_id = a.id
LEFT JOIN seg_last sl    ON sl.account_id = a.id
LEFT JOIN bg             ON bg.account_id = a.id
LEFT JOIN pause_resume pr ON pr.account_id = a.id
WHERE a.id <> '00000000-0000-0000-0000-000000000001';

-- --- Country reports ----------------------------------------------------------
--
-- READ THIS BEFORE USING THE NUMBERS BELOW.
--
-- country_code is what the player chose on a screen. It is NOT verified, NOT
-- derived from an IP address, and NOT an install attribution. In particular:
-- these views cannot be joined one-to-one to Google Ads (or any store) install
-- reports by country. Ad networks report the country their own attribution
-- assigns; this column reports what the player tapped. No supported mechanism
-- links an ad click to an account here — there is no install-referrer or
-- attribution id anywhere in this schema — so the two will differ, and treating
-- a difference as a discrepancy to reconcile would be a mistake.
--
-- Accounts with no country appear under 'UNKNOWN' in these views. That bucket is
-- the migration window made visible: it is players who registered before the
-- country screen existed, and it should shrink toward a floor after the client
-- release rather than to zero.

-- 2. Total accounts by country, with the never-asked bucket.
CREATE OR REPLACE VIEW admin_accounts_by_country AS
SELECT COALESCE(a.country_code, 'UNKNOWN')                    AS country_code,
       COUNT(*)                                               AS account_count,
       COUNT(*) FILTER (WHERE a.country_code IS NULL)         AS country_unset_count,
       MIN(a.created_at)                                      AS first_account_at,
       MAX(a.created_at)                                      AS latest_account_at,
       COUNT(*) FILTER (WHERE a.last_seen_at >= now() - INTERVAL '7 days')
                                                              AS active_last_7_days
FROM accounts a
WHERE a.id <> '00000000-0000-0000-0000-000000000001'
GROUP BY 1;

-- 1 and 7. New accounts by country per Istanbul day. The UNKNOWN row on any day
-- after the country release is the count still arriving without a country, which
-- is how the rollout is watched.
CREATE OR REPLACE VIEW admin_daily_new_accounts_by_country AS
SELECT (a.created_at AT TIME ZONE 'Europe/Istanbul')::date    AS day,
       COALESCE(a.country_code, 'UNKNOWN')                    AS country_code,
       COUNT(*)                                               AS new_accounts
FROM accounts a
WHERE a.id <> '00000000-0000-0000-0000-000000000001'
GROUP BY 1, 2;

-- 5. Sessions by the account's selected country. Sessions are attributed to the
-- day they STARTED, matching admin_daily_players (V78).
CREATE OR REPLACE VIEW admin_sessions_by_country AS
SELECT (s.started_at AT TIME ZONE 'Europe/Istanbul')::date    AS day,
       COALESCE(a.country_code, 'UNKNOWN')                    AS country_code,
       COUNT(*)                                               AS session_count,
       COUNT(DISTINCT s.account_id)                           AS player_count,
       ROUND(SUM(GREATEST(0, EXTRACT(EPOCH FROM
           (COALESCE(s.ended_at, s.last_activity_at) - s.started_at)))) / 60.0, 1)
                                                              AS estimated_minutes
FROM player_sessions s
JOIN accounts a ON a.id = s.account_id
WHERE a.id <> '00000000-0000-0000-0000-000000000001'
GROUP BY 1, 2;

-- 3, 4 and 6. The onboarding funnel, per country, per Istanbul day.
--
-- Only four steps carry a country on the row itself, so every other step gets
-- its country from the installation's own country_selected event. That is the
-- point of the installation_country CTE: without it a nickname_rejected could
-- never be attributed, and "which countries are blocked at the nickname screen"
-- is one of the questions this whole feature exists to answer.
--
-- An installation that quit before the country screen has no country at all and
-- lands in 'UNKNOWN'. Those rows are real — they are the players who left
-- earliest — so they are shown rather than dropped.
CREATE OR REPLACE VIEW admin_onboarding_funnel_by_country AS
WITH installation_country AS (
    SELECT DISTINCT ON (installation_id)
           installation_id,
           country_code
    FROM onboarding_events
    WHERE country_code IS NOT NULL
    ORDER BY installation_id, received_at DESC
),
resolved AS (
    SELECT (e.received_at AT TIME ZONE 'Europe/Istanbul')::date        AS day,
           COALESCE(e.country_code, ic.country_code, 'UNKNOWN')        AS country_code,
           e.installation_id                                           AS installation_id,
           e.event_name                                                AS event_name,
           e.rejection_reason                                          AS rejection_reason
    FROM onboarding_events e
    LEFT JOIN installation_country ic ON ic.installation_id = e.installation_id
)
SELECT day                                        AS day,
       country_code                               AS country_code,
       COUNT(DISTINCT installation_id)
           FILTER (WHERE event_name = 'app_launched')             AS app_launched,
       COUNT(DISTINCT installation_id)
           FILTER (WHERE event_name = 'country_screen_shown')     AS country_screen_shown,
       COUNT(DISTINCT installation_id)
           FILTER (WHERE event_name = 'country_selected')         AS country_selected,
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
FROM resolved
GROUP BY 1, 2;

-- 4. Why players are blocked at the nickname screen, split by the country they
-- selected. A rule that costs us one country far more than the others shows up
-- here and nowhere else.
CREATE OR REPLACE VIEW admin_nickname_rejections_by_country AS
WITH installation_country AS (
    SELECT DISTINCT ON (installation_id)
           installation_id,
           country_code
    FROM onboarding_events
    WHERE country_code IS NOT NULL
    ORDER BY installation_id, received_at DESC
)
SELECT (e.received_at AT TIME ZONE 'Europe/Istanbul')::date   AS day,
       COALESCE(ic.country_code, 'UNKNOWN')                   AS country_code,
       COALESCE(e.rejection_reason, 'UNSPECIFIED')            AS rejection_reason,
       COUNT(*)                                               AS rejection_events,
       COUNT(DISTINCT e.installation_id)                      AS installations
FROM onboarding_events e
LEFT JOIN installation_country ic ON ic.installation_id = e.installation_id
WHERE e.event_name = 'nickname_rejected'
GROUP BY 1, 2, 3;
