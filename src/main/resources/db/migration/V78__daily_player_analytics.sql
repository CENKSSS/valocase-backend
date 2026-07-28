-- ValoCase backend - daily player activity roll-up (backend-only, read-only).
--
-- The existing V75/V76 views answer "who is online now" and "what has this
-- player done in total", but nothing answers "how many players played today and
-- for how long". These two views add exactly that. They are plain views over
-- data that is already recorded, so nothing new is written, no client change is
-- needed, and dropping them would lose no data.
--
-- DAY BOUNDARY: timestamps are stored as timestamptz (UTC). A "day" here is an
-- Istanbul day, because that is the day the players actually live in — a
-- session at 01:00 Istanbul belongs to that morning, not to the previous UTC
-- day. A session is attributed entirely to the day it STARTED, so a session
-- crossing midnight counts once, on its starting day.
--
-- ACCURACY: estimated_minutes inherits the honesty rules documented in
-- docs/ANALYTICS.md — a session's duration is last observed activity minus
-- start, so time played after the player's final request is NOT counted and a
-- single-request session is 0 minutes. active_foreground_minutes is the precise
-- figure, but only for clients that report the V76 session lifecycle; it is 0
-- for sessions estimated from plain request activity.
--
-- The per-session values are shaped in the `daily` CTE so the outer query groups
-- only by plain columns; nothing in the select list is a recomputed expression.

CREATE OR REPLACE VIEW admin_daily_players AS
WITH foreground AS (
    SELECT g.session_id,
           SUM(GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at))))
               AS foreground_seconds
    FROM player_session_segments g
    JOIN player_sessions ps ON ps.id = g.session_id
    GROUP BY g.session_id
),
daily AS (
    SELECT (s.started_at AT TIME ZONE 'Europe/Istanbul')::date   AS day,
           (a.created_at AT TIME ZONE 'Europe/Istanbul')::date   AS registered_day,
           s.account_id                                          AS account_id,
           a.display_name                                        AS display_name,
           a.level                                               AS level,
           s.started_at                                          AS started_at,
           COALESCE(s.ended_at, s.last_activity_at)              AS ended_at,
           GREATEST(0, EXTRACT(EPOCH FROM
               (COALESCE(s.ended_at, s.last_activity_at) - s.started_at)))
                                                                 AS session_seconds,
           COALESCE(f.foreground_seconds, 0)                     AS foreground_seconds
    FROM player_sessions s
    JOIN accounts a ON a.id = s.account_id
    LEFT JOIN foreground f ON f.session_id = s.id
    WHERE a.id <> '00000000-0000-0000-0000-000000000001'
)
SELECT day                                       AS day,
       account_id                                AS user_id,
       display_name                              AS username,
       level                                     AS level,
       COUNT(*)                                  AS session_count,
       ROUND(SUM(session_seconds) / 60.0, 1)     AS estimated_minutes,
       ROUND(SUM(foreground_seconds) / 60.0, 1)  AS active_foreground_minutes,
       MIN(started_at)                           AS first_session_at,
       MAX(ended_at)                             AS last_activity_at,
       (day = registered_day)                    AS is_new_user
FROM daily
GROUP BY day, account_id, display_name, level, registered_day;

CREATE OR REPLACE VIEW admin_daily_summary AS
SELECT day                                       AS day,
       COUNT(*)                                  AS player_count,
       COUNT(*) FILTER (WHERE is_new_user)       AS new_player_count,
       SUM(session_count)                        AS session_count,
       ROUND(SUM(estimated_minutes), 1)          AS total_minutes,
       ROUND(AVG(estimated_minutes), 1)          AS avg_minutes_per_player,
       ROUND(MAX(estimated_minutes), 1)          AS max_minutes_by_a_player
FROM admin_daily_players
GROUP BY day;
