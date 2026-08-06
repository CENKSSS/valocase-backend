-- ValoCase backend - reporting that names its own timezone.
--
-- THE PROBLEM WITH WHAT IS HERE ALREADY
-- V78, V79 and V80 fix the reporting day to Europe/Istanbul in eleven places.
-- That was right when the only players were in Turkey. It is wrong now: the ads
-- run in India, and an Istanbul day boundary splits an Indian player's evening
-- across two report rows. The difference is not theoretical -- 2026-08-04 shows
-- 3 new accounts on the Istanbul day and 5 on the Kolkata day.
--
-- WHY THE OLD VIEWS ARE NOT SIMPLY REPOINTED
-- Rewriting them to Asia/Kolkata would silently change every historical number
-- anyone has already read, and would be just as wrong the day a third country is
-- added. A report has to say which day it means. So the Istanbul views stay
-- exactly as they are -- byte for byte, still the default -- and the timezone
-- becomes an argument the caller supplies.
--
-- WHY FUNCTIONS RATHER THAN MORE VIEWS
-- A PostgreSQL view cannot take a parameter. One view per timezone would be 400+
-- views and would still not cover the next country. A function takes the zone as
-- an argument and validates it against pg_timezone_names, so a typo is an error
-- rather than a silently wrong report.
--
-- STORAGE IS UNCHANGED AND STAYS UTC
-- Nothing here writes, alters or moves a row. Every timestamp remains TIMESTAMPTZ
-- (stored UTC); the zone affects only where a day is cut. Additive and fully
-- reversible: DROP FUNCTION restores the previous state exactly.

-- --- Timezone validation ------------------------------------------------------
--
-- Rejecting an unknown zone matters more than it looks. PostgreSQL's
-- `AT TIME ZONE 'Asia/Kolkatta'` does not return NULL or fall back to UTC -- it
-- raises. Validating first turns a typo into one clear message instead of an
-- error from deep inside a CTE, and stops a caller passing a fixed offset like
-- '+05:30' that would silently ignore any future DST rule.

-- STABLE, not IMMUTABLE: this reads pg_timezone_names, and a function that consults a
-- catalog is stable at best. Labelling it immutable would invite the planner to fold it
-- away on the strength of a promise it does not keep.
CREATE OR REPLACE FUNCTION admin_require_timezone(tz TEXT)
RETURNS TEXT
LANGUAGE plpgsql
STABLE
AS $$
BEGIN
    IF tz IS NULL OR btrim(tz) = '' THEN
        RAISE EXCEPTION 'timezone is required, e.g. Asia/Kolkata or Europe/Istanbul';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_timezone_names WHERE name = tz) THEN
        RAISE EXCEPTION 'unknown timezone: %. Use an IANA name from pg_timezone_names, '
                        'e.g. Asia/Kolkata or Europe/Istanbul', tz;
    END IF;
    RETURN tz;
END;
$$;

COMMENT ON FUNCTION admin_require_timezone(TEXT) IS
'Validates an IANA timezone name and returns it, or raises. Used by the admin_*_tz
reporting functions so an unknown zone fails loudly instead of producing a report
for the wrong day.';

-- --- Per-player daily report, in the caller''s timezone ------------------------
--
-- The Istanbul-only equivalent is the admin_daily_players view (V78), which stays
-- in place. This is that view with the day boundary lifted out into an argument;
-- the arithmetic below is otherwise identical, deliberately, so the two cannot
-- drift into disagreeing about what a session is worth.

CREATE OR REPLACE FUNCTION admin_daily_players_tz(tz TEXT)
RETURNS TABLE (
    day                       DATE,
    user_id                   UUID,
    username                  VARCHAR,
    level                     INTEGER,
    session_count             BIGINT,
    estimated_minutes         NUMERIC,
    active_foreground_minutes NUMERIC,
    first_session_at          TIMESTAMPTZ,
    last_activity_at          TIMESTAMPTZ,
    is_new_user               BOOLEAN
)
LANGUAGE sql
STABLE
AS $$
    WITH zone AS (SELECT admin_require_timezone(tz) AS tz),
    foreground AS (
        SELECT g.session_id,
               SUM(GREATEST(0, EXTRACT(EPOCH FROM
                   (COALESCE(g.ended_at, ps.last_heartbeat_at, g.started_at) - g.started_at))))
                   AS foreground_seconds
        FROM player_session_segments g
        JOIN player_sessions ps ON ps.id = g.session_id
        GROUP BY g.session_id
    ),
    daily AS (
        SELECT (s.started_at AT TIME ZONE z.tz)::date                AS day,
               (a.created_at AT TIME ZONE z.tz)::date                AS registered_day,
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
        CROSS JOIN zone z
        JOIN accounts a ON a.id = s.account_id
        LEFT JOIN foreground f ON f.session_id = s.id
        WHERE a.id <> '00000000-0000-0000-0000-000000000001'
    )
    SELECT day,
           account_id,
           display_name,
           level,
           COUNT(*),
           ROUND(SUM(session_seconds) / 60.0, 1),
           ROUND(SUM(foreground_seconds) / 60.0, 1),
           MIN(started_at),
           MAX(ended_at),
           (day = registered_day)
    FROM daily
    GROUP BY day, account_id, display_name, level, registered_day;
$$;

COMMENT ON FUNCTION admin_daily_players_tz(TEXT) IS
'admin_daily_players with the reporting day cut in the given IANA timezone.
Storage stays UTC; only the day boundary moves. SELECT * FROM
admin_daily_players_tz(''Asia/Kolkata'').';

-- --- Daily summary, in the caller''s timezone ---------------------------------

CREATE OR REPLACE FUNCTION admin_daily_summary_tz(tz TEXT)
RETURNS TABLE (
    day                     DATE,
    player_count            BIGINT,
    new_player_count        BIGINT,
    session_count           BIGINT,
    total_minutes           NUMERIC,
    avg_minutes_per_player  NUMERIC,
    max_minutes_by_a_player NUMERIC
)
LANGUAGE sql
STABLE
AS $$
    -- SUM() over a bigint returns numeric, not bigint. Without the cast the function
    -- body does not match its own RETURNS TABLE and CREATE FUNCTION fails outright --
    -- which, running under Flyway at startup, is an application that will not boot.
    -- The equivalent V78 view hides this because a view infers its column types.
    SELECT day,
           COUNT(*),
           COUNT(*) FILTER (WHERE is_new_user),
           SUM(session_count)::BIGINT,
           ROUND(SUM(estimated_minutes), 1),
           ROUND(AVG(estimated_minutes), 1),
           ROUND(MAX(estimated_minutes), 1)
    FROM admin_daily_players_tz(tz)
    GROUP BY day;
$$;

COMMENT ON FUNCTION admin_daily_summary_tz(TEXT) IS
'admin_daily_summary with the reporting day cut in the given IANA timezone.
SELECT * FROM admin_daily_summary_tz(''Asia/Kolkata'') ORDER BY day DESC.';

-- --- Onboarding funnel, in the caller''s timezone ------------------------------
--
-- Counts DISTINCT installations rather than events, for the same reason the
-- Istanbul view does: a player who mistypes a nickname four times sends four
-- nickname_rejected events and is still one blocked player.

CREATE OR REPLACE FUNCTION admin_onboarding_funnel_tz(tz TEXT)
RETURNS TABLE (
    day                      DATE,
    app_launched             BIGINT,
    fan_notice_shown         BIGINT,
    fan_notice_accepted      BIGINT,
    nickname_screen_shown    BIGINT,
    nickname_rejected        BIGINT,
    nickname_confirm_clicked BIGINT,
    registration_attempted   BIGINT,
    registration_failed      BIGINT,
    registration_succeeded   BIGINT,
    account_created          BIGINT,
    session_created          BIGINT
)
LANGUAGE sql
STABLE
AS $$
    WITH zone AS (SELECT admin_require_timezone(tz) AS tz),
    per_day AS (
        SELECT (e.received_at AT TIME ZONE z.tz)::date                AS day,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'app_launched')             AS app_launched,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'fan_notice_shown')         AS fan_notice_shown,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'fan_notice_accepted')      AS fan_notice_accepted,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'nickname_screen_shown')    AS nickname_screen_shown,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'nickname_rejected')        AS nickname_rejected,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'nickname_confirm_clicked') AS nickname_confirm_clicked,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'registration_attempted')   AS registration_attempted,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'registration_failed')      AS registration_failed,
               COUNT(DISTINCT e.installation_id)
                   FILTER (WHERE e.event_name = 'registration_succeeded')   AS registration_succeeded
        FROM onboarding_events e CROSS JOIN zone z
        GROUP BY 1
    ),
    accounts_per_day AS (
        SELECT (a.created_at AT TIME ZONE z.tz)::date AS day, COUNT(*) AS account_created
        FROM accounts a CROSS JOIN zone z
        WHERE a.id <> '00000000-0000-0000-0000-000000000001'
        GROUP BY 1
    ),
    sessions_per_day AS (
        SELECT (s.started_at AT TIME ZONE z.tz)::date AS day,
               COUNT(DISTINCT s.account_id) AS session_created
        FROM player_sessions s CROSS JOIN zone z
        WHERE s.account_id <> '00000000-0000-0000-0000-000000000001'
        GROUP BY 1
    )
    SELECT COALESCE(p.day, a.day, s.day),
           COALESCE(p.app_launched, 0),
           COALESCE(p.fan_notice_shown, 0),
           COALESCE(p.fan_notice_accepted, 0),
           COALESCE(p.nickname_screen_shown, 0),
           COALESCE(p.nickname_rejected, 0),
           COALESCE(p.nickname_confirm_clicked, 0),
           COALESCE(p.registration_attempted, 0),
           COALESCE(p.registration_failed, 0),
           COALESCE(p.registration_succeeded, 0),
           COALESCE(a.account_created, 0),
           COALESCE(s.session_created, 0)
    FROM per_day p
    FULL JOIN accounts_per_day a ON a.day = p.day
    FULL JOIN sessions_per_day s ON s.day = COALESCE(p.day, a.day);
$$;

COMMENT ON FUNCTION admin_onboarding_funnel_tz(TEXT) IS
'admin_onboarding_funnel with the reporting day cut in the given IANA timezone.
account_created and session_created are day totals from the canonical tables, not
per-installation joins. SELECT * FROM admin_onboarding_funnel_tz(''Asia/Kolkata'').';
