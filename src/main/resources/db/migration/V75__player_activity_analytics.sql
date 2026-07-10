-- ValoCase backend - backend-only player activity analytics.
--
-- player_sessions holds server-ESTIMATED play sessions derived purely from
-- authenticated request activity. The released Unity client sends no heartbeat,
-- logout, pause or resume events, so exact session ends are unknowable:
-- a session is continued while authenticated requests keep arriving within the
-- inactivity timeout (default 5 minutes) and is closed with
-- end_reason = 'INACTIVITY_TIMEOUT' at the last observed activity once the
-- timeout elapses. is_estimated is always TRUE for these rows.
--
-- player_activity_events records only gameplay facts that have no canonical
-- table. Today that is SKINS_SOLD: bulk sells delete the inventory rows and
-- write a single wallet transaction, so the per-item count would otherwise be
-- lost. Everything else (case openings, battles, upgrades, rewards, wallet
-- history) is derived from the existing canonical tables by the admin_* views
-- below - nothing is double-recorded.
--
-- Additive only: no existing table, row or timestamp is modified. All
-- timestamps are TIMESTAMPTZ (the project convention; Instant <-> timestamptz).
-- Column types/nullability match the JPA entities so ddl-auto=validate passes.

CREATE TABLE IF NOT EXISTS player_sessions (
    id               UUID PRIMARY KEY,
    account_id       UUID        NOT NULL REFERENCES accounts (id),
    started_at       TIMESTAMPTZ NOT NULL,
    last_activity_at TIMESTAMPTZ NOT NULL,
    ended_at         TIMESTAMPTZ,
    duration_seconds BIGINT,
    end_reason       VARCHAR(30),
    is_estimated     BOOLEAN     NOT NULL DEFAULT TRUE,
    platform         VARCHAR(30),
    app_version      VARCHAR(50)
);

-- At most one open (ended_at IS NULL) session per account; the service falls
-- back to updating the existing open session when a concurrent insert collides.
CREATE UNIQUE INDEX IF NOT EXISTS uq_player_sessions_open
    ON player_sessions (account_id) WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_player_sessions_account_started
    ON player_sessions (account_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_player_sessions_last_activity
    ON player_sessions (last_activity_at);

CREATE INDEX IF NOT EXISTS idx_player_sessions_started
    ON player_sessions (started_at);

CREATE TABLE IF NOT EXISTS player_activity_events (
    id           UUID PRIMARY KEY,
    account_id   UUID        NOT NULL REFERENCES accounts (id),
    event_type   VARCHAR(40) NOT NULL,
    source       VARCHAR(50),
    reference_id UUID,
    quantity     INTEGER,
    vp_amount    BIGINT,
    occurred_at  TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_player_activity_events_account_occurred
    ON player_activity_events (account_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_player_activity_events_type_occurred
    ON player_activity_events (event_type, occurred_at);

-- Supporting indexes on existing tables for the analytics queries below.
-- Purely additive; no data is touched.

CREATE INDEX IF NOT EXISTS idx_accounts_last_seen_at
    ON accounts (last_seen_at);

CREATE INDEX IF NOT EXISTS idx_wallet_tx_wallet_created
    ON wallet_transactions (wallet_id, created_at);

CREATE INDEX IF NOT EXISTS idx_wallet_tx_reason_created
    ON wallet_transactions (reason, created_at);

CREATE INDEX IF NOT EXISTS idx_wallet_tx_created
    ON wallet_transactions (created_at);

CREATE INDEX IF NOT EXISTS idx_case_openings_account_created
    ON case_openings (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_battles_account_created
    ON battles (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_upgrades_account_created
    ON upgrades (account_id, created_at);

CREATE INDEX IF NOT EXISTS idx_battle_lobby_slots_account
    ON battle_lobby_slots (account_id) WHERE account_id IS NOT NULL;

-- --- Admin views for DataGrip ------------------------------------------------
--
-- Every aggregate is computed per source in its own subquery and only then
-- joined on account id, so one-to-many joins can never multiply totals.
-- The online threshold baked into these views is 5 minutes; users may appear
-- online for up to that long after force-closing the game because the client
-- sends no logout or heartbeat.
-- The fixed system event account (V71) is excluded everywhere.

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
                ua.last_upgrade_at, wa.last_sell_at) AS last_gameplay_at
FROM accounts a
LEFT JOIN wallets w      ON w.account_id  = a.id
LEFT JOIN session_agg sa ON sa.account_id = a.id
LEFT JOIN last_session ls ON ls.account_id = a.id
LEFT JOIN wallet_agg wa  ON wa.account_id = a.id
LEFT JOIN battle_agg ba  ON ba.account_id = a.id
LEFT JOIN upgrade_agg ua ON ua.account_id = a.id
LEFT JOIN case_agg ca    ON ca.account_id = a.id
LEFT JOIN sold_agg so    ON so.account_id = a.id
WHERE a.id <> '00000000-0000-0000-0000-000000000001';

CREATE OR REPLACE VIEW admin_current_online_users AS
SELECT a.id                                           AS user_id,
       a.display_name                                 AS username,
       a.level                                        AS level,
       COALESCE(w.vp_balance, 0)                      AS current_vp_balance,
       a.last_seen_at                                 AS last_activity_at,
       ps.started_at                                  AS session_started_at,
       ROUND(GREATEST(0, EXTRACT(EPOCH FROM (now() - ps.started_at))) / 60.0, 1)
                                                      AS session_minutes_so_far
FROM accounts a
LEFT JOIN wallets w ON w.account_id = a.id
LEFT JOIN player_sessions ps ON ps.account_id = a.id AND ps.ended_at IS NULL
WHERE a.status = 'ACTIVE'
  AND a.id <> '00000000-0000-0000-0000-000000000001'
  AND a.last_seen_at >= now() - INTERVAL '5 minutes';

CREATE OR REPLACE VIEW admin_recent_sessions AS
SELECT s.id                                           AS session_id,
       s.account_id                                   AS user_id,
       a.display_name                                 AS username,
       s.started_at,
       s.last_activity_at,
       CASE WHEN s.ended_at IS NOT NULL THEN s.ended_at
            WHEN s.last_activity_at < now() - INTERVAL '5 minutes' THEN s.last_activity_at
       END                                            AS estimated_ended_at,
       ROUND(GREATEST(0, EXTRACT(EPOCH FROM
           (COALESCE(s.ended_at, s.last_activity_at) - s.started_at))) / 60.0, 1)
                                                      AS estimated_minutes,
       (s.ended_at IS NULL
            AND s.last_activity_at >= now() - INTERVAL '5 minutes') AS is_active,
       s.end_reason,
       s.is_estimated
FROM player_sessions s
JOIN accounts a ON a.id = s.account_id;

CREATE OR REPLACE VIEW admin_wallet_transaction_history AS
SELECT t.id                                           AS transaction_id,
       w.account_id                                   AS user_id,
       a.display_name                                 AS username,
       t.delta                                        AS amount,
       t.balance_after - t.delta                      AS balance_before,
       t.balance_after,
       CASE WHEN t.delta >= 0 THEN 'CREDIT' ELSE 'DEBIT' END AS direction,
       t.reason                                       AS source,
       t.reference_id,
       t.created_at
FROM wallet_transactions t
JOIN wallets w ON w.id = t.wallet_id
JOIN accounts a ON a.id = w.account_id;

CREATE OR REPLACE VIEW admin_battle_analytics AS
WITH per_user AS (
    SELECT account_id,
           SUM(joined)    AS battles_joined,
           SUM(completed) AS battles_completed,
           SUM(wins)      AS battles_won,
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
spend AS (
    SELECT w.account_id,
           SUM(CASE WHEN t.reason IN ('BATTLE_ENTRY', 'BATTLE_LOBBY_ENTRY')
                    THEN -t.delta ELSE 0 END) AS vp_spent_on_battles,
           SUM(CASE WHEN t.reason = 'BATTLE_LOBBY_REFUND'
                    THEN t.delta ELSE 0 END)  AS vp_refunded_from_battles
    FROM wallet_transactions t
    JOIN wallets w ON w.id = t.wallet_id
    WHERE t.reason IN ('BATTLE_ENTRY', 'BATTLE_LOBBY_ENTRY', 'BATTLE_LOBBY_REFUND')
    GROUP BY w.account_id
),
rewards AS (
    SELECT recipient AS account_id, SUM(vp_value) AS reward_skin_value
    FROM (
        SELECT CASE WHEN bl.id IS NOT NULL THEN ws.account_id
                    ELSE b.account_id END AS recipient,
               r.vp_value
        FROM battle_rolls r
        JOIN battles b ON b.id = r.battle_id
        LEFT JOIN battle_lobbies bl ON bl.result_battle_id = b.id
        LEFT JOIN battle_lobby_slots ws ON ws.lobby_id = bl.id
                                        AND ws.slot_index = bl.winner_slot_index
        WHERE r.granted_inventory_item_id IS NOT NULL
    ) g
    WHERE recipient IS NOT NULL
    GROUP BY recipient
)
SELECT a.id                                          AS user_id,
       a.display_name                                AS username,
       COALESCE(pu.battles_joined, 0) > 0            AS has_ever_battled,
       COALESCE(pu.battles_joined, 0)                AS battles_joined,
       COALESCE(pu.battles_completed, 0)             AS battles_completed,
       COALESCE(pu.battles_won, 0)                   AS battles_won,
       COALESCE(pu.battles_completed, 0)
           - COALESCE(pu.battles_won, 0)             AS battles_lost,
       COALESCE(sp.vp_spent_on_battles, 0)           AS vp_spent_on_battles,
       COALESCE(sp.vp_refunded_from_battles, 0)      AS vp_refunded_from_battles,
       COALESCE(rw.reward_skin_value, 0)             AS reward_skin_value_won,
       pu.last_battle_at
FROM accounts a
LEFT JOIN per_user pu ON pu.account_id = a.id
LEFT JOIN spend sp    ON sp.account_id = a.id
LEFT JOIN rewards rw  ON rw.account_id = a.id
WHERE a.id <> '00000000-0000-0000-0000-000000000001';

CREATE OR REPLACE VIEW admin_upgrade_analytics AS
SELECT a.id                                          AS user_id,
       a.display_name                                AS username,
       COUNT(u.id) > 0                               AS has_ever_upgraded,
       COUNT(u.id)                                   AS upgrade_attempts,
       COUNT(u.id) FILTER (WHERE u.success)          AS upgrade_successes,
       COUNT(u.id) FILTER (WHERE NOT u.success)      AS upgrade_failures,
       COALESCE(SUM(u.input_value), 0)               AS input_value_consumed,
       COALESCE(SUM(u.target_value) FILTER (WHERE u.success), 0) AS reward_value_granted,
       MAX(u.created_at)                             AS last_upgrade_at
FROM accounts a
LEFT JOIN upgrades u ON u.account_id = a.id
WHERE a.id <> '00000000-0000-0000-0000-000000000001'
GROUP BY a.id, a.display_name;
