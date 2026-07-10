-- ValoCase backend - precise client-reported session lifecycle (extends V75).
--
-- V75 sessions are ESTIMATED from authenticated request activity. This migration
-- adds an opt-in precise protocol: the Unity client reports app start, heartbeat
-- (~30s while foregrounded), pause, resume and a best-effort graceful end. The
-- server clock stays authoritative for every stored timestamp and duration; the
-- client only supplies its own session id, installation id, app version,
-- platform, a monotonic lifecycle sequence and a diagnostic-only sent-at.
--
-- player_sessions rows with a client_session_id are precise; rows without one
-- remain the V75 request-estimated sessions and keep working unchanged.
-- player_session_segments records each foreground interval so active foreground
-- time excludes backgrounded time. Additive and idempotent only: no existing
-- table, row or timestamp is modified; all timestamps are TIMESTAMPTZ.

ALTER TABLE player_sessions
    ADD COLUMN IF NOT EXISTS client_session_id  UUID,
    ADD COLUMN IF NOT EXISTS installation_id    UUID,
    ADD COLUMN IF NOT EXISTS lifecycle_sequence BIGINT,
    ADD COLUMN IF NOT EXISTS lifecycle_state    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS last_heartbeat_at  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS explicit_ended_at  TIMESTAMPTZ;

-- One logical session per (account, clientSessionId); NULL client ids (V75 rows)
-- are distinct in Postgres so they never collide with this.
CREATE UNIQUE INDEX IF NOT EXISTS uq_player_sessions_account_client
    ON player_sessions (account_id, client_session_id) WHERE client_session_id IS NOT NULL;

-- Supports the timeout scanner (few open client sessions at a time).
CREATE INDEX IF NOT EXISTS idx_player_sessions_open_client
    ON player_sessions (last_activity_at)
    WHERE ended_at IS NULL AND client_session_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS player_session_segments (
    id           UUID PRIMARY KEY,
    session_id   UUID        NOT NULL REFERENCES player_sessions (id),
    started_at   TIMESTAMPTZ NOT NULL,
    ended_at     TIMESTAMPTZ,
    end_reason   VARCHAR(30),
    is_estimated BOOLEAN     NOT NULL DEFAULT FALSE
);

-- At most one open foreground segment per session.
CREATE UNIQUE INDEX IF NOT EXISTS uq_player_session_segments_open
    ON player_session_segments (session_id) WHERE ended_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_player_session_segments_session
    ON player_session_segments (session_id, started_at);

-- --- Views -------------------------------------------------------------------
--
-- Production thresholds baked below (a client can't read Spring config):
--   * Online = open session, lifecycle_state = 'FOREGROUND', and a heartbeat
--     within 90 seconds. Paused/background sessions are never online. A
--     force-closed app stays online at most until its heartbeat ages past 90s.
--   * Foreground/background minutes come only from closed+current segments;
--     the open segment is measured up to its last server-observed heartbeat,
--     never to now(), so no client exit time is ever invented.
-- The existing V75 columns are preserved in place; lifecycle columns are
-- appended (CREATE OR REPLACE requires identical leading columns).

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
       cs.client_session_id                          AS current_client_session_id
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

CREATE OR REPLACE VIEW admin_current_online_users AS
SELECT a.id                                           AS user_id,
       a.display_name                                 AS username,
       a.level                                        AS level,
       COALESCE(w.vp_balance, 0)                      AS current_vp_balance,
       a.last_seen_at                                 AS last_activity_at,
       ps.started_at                                  AS session_started_at,
       ROUND(GREATEST(0, EXTRACT(EPOCH FROM (now() - ps.started_at))) / 60.0, 1)
                                                      AS session_minutes_so_far,
       cs.last_heartbeat_at                           AS last_heartbeat_at,
       cs.lifecycle_state                             AS current_lifecycle_state,
       cs.app_version                                 AS current_app_version,
       cs.platform                                    AS current_platform,
       cs.client_session_id                           AS current_client_session_id,
       (cs.ended_at IS NULL AND cs.lifecycle_state = 'FOREGROUND'
            AND cs.last_heartbeat_at >= now() - INTERVAL '90 seconds') AS is_client_online
FROM accounts a
LEFT JOIN wallets w ON w.account_id = a.id
LEFT JOIN player_sessions ps ON ps.account_id = a.id AND ps.ended_at IS NULL
LEFT JOIN LATERAL (
    SELECT c.ended_at, c.last_heartbeat_at, c.lifecycle_state, c.app_version,
           c.platform, c.client_session_id
    FROM player_sessions c
    WHERE c.account_id = a.id AND c.client_session_id IS NOT NULL
    ORDER BY c.started_at DESC
    LIMIT 1
) cs ON TRUE
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
       s.is_estimated,
       s.client_session_id,
       s.installation_id,
       s.app_version,
       s.platform,
       s.lifecycle_state,
       s.last_heartbeat_at,
       s.explicit_ended_at,
       ROUND(COALESCE(fs.foreground_seconds, 0) / 60.0, 1) AS active_foreground_minutes,
       CASE WHEN s.client_session_id IS NULL THEN 'ESTIMATED_REQUEST'
            WHEN s.explicit_ended_at IS NOT NULL THEN 'EXPLICIT'
            WHEN s.ended_at IS NOT NULL THEN 'TIMEOUT'
            ELSE 'OPEN'
       END                                            AS close_type
FROM player_sessions s
JOIN accounts a ON a.id = s.account_id
LEFT JOIN (
    SELECT g.session_id,
           SUM(GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at)))) AS foreground_seconds
    FROM player_session_segments g
    JOIN player_sessions ps ON ps.id = g.session_id
    GROUP BY g.session_id
) fs ON fs.session_id = s.id;

CREATE OR REPLACE VIEW admin_session_segments AS
SELECT g.id                                           AS segment_id,
       g.session_id,
       ps.account_id                                  AS user_id,
       a.display_name                                 AS username,
       ps.client_session_id,
       g.started_at,
       g.ended_at,
       g.end_reason,
       g.is_estimated,
       ROUND(GREATEST(0, EXTRACT(EPOCH FROM
           (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at))) / 60.0, 1)
                                                      AS active_minutes
FROM player_session_segments g
JOIN player_sessions ps ON ps.id = g.session_id
JOIN accounts a ON a.id = ps.account_id;

CREATE OR REPLACE VIEW admin_client_presence AS
WITH latest AS (
    SELECT DISTINCT ON (account_id)
           account_id, id AS session_id, started_at, ended_at, last_activity_at,
           last_heartbeat_at, explicit_ended_at, lifecycle_state, lifecycle_sequence,
           app_version, platform, installation_id, client_session_id, end_reason, is_estimated
    FROM player_sessions
    WHERE client_session_id IS NOT NULL
    ORDER BY account_id, started_at DESC
),
seg_sum AS (
    SELECT ps.account_id,
           SUM(GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at)))) AS total_foreground_seconds
    FROM player_session_segments g
    JOIN player_sessions ps ON ps.id = g.session_id
    WHERE ps.client_session_id IS NOT NULL
    GROUP BY ps.account_id
),
last_seg AS (
    SELECT g.session_id,
           SUM(GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at)))) AS foreground_seconds
    FROM player_session_segments g
    JOIN player_sessions ps ON ps.id = g.session_id
    GROUP BY g.session_id
)
SELECT l.account_id                                   AS user_id,
       a.display_name                                 AS username,
       l.client_session_id,
       l.installation_id,
       l.app_version,
       l.platform,
       l.lifecycle_state,
       l.lifecycle_sequence,
       l.started_at                                   AS session_started_at,
       l.ended_at                                     AS session_ended_at,
       l.last_heartbeat_at,
       l.explicit_ended_at,
       CASE WHEN l.explicit_ended_at IS NOT NULL THEN 'EXPLICIT'
            WHEN l.ended_at IS NOT NULL THEN 'TIMEOUT'
            ELSE 'OPEN'
       END                                            AS close_type,
       l.is_estimated,
       ROUND(COALESCE(ls.foreground_seconds, 0) / 60.0, 1) AS last_session_active_minutes,
       ROUND(COALESCE(ss.total_foreground_seconds, 0) / 60.0, 1) AS total_active_foreground_minutes,
       (l.ended_at IS NULL AND l.lifecycle_state = 'FOREGROUND'
            AND l.last_heartbeat_at >= now() - INTERVAL '90 seconds') AS is_online
FROM latest l
JOIN accounts a ON a.id = l.account_id
LEFT JOIN seg_sum ss ON ss.account_id = l.account_id
LEFT JOIN last_seg ls ON ls.session_id = l.session_id
WHERE l.account_id <> '00000000-0000-0000-0000-000000000001';
