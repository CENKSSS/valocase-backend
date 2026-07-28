# Player Activity & Gameplay Analytics (backend-only)

Added by migration `V75__player_activity_analytics.sql`. Everything here works
purely from the requests the released Unity client already sends; no client
change is required or assumed.

## What is exact vs. estimated

Exact (from canonical tables that already existed):

- Every VP change: `wallet_transactions` is written in the same database
  transaction as the balance update (`WalletService.applyDelta`). Amount,
  reason (source), reference id, `balance_after` are stored;
  `balance_before = balance_after - delta`.
- Case openings (`case_openings`), battles (`battles`, `battle_lobbies`,
  `battle_lobby_slots`, `battle_rolls`), upgrades (`upgrades`,
  `upgrade_inputs`, `upgrade_targets`), daily rewards (`daily_claims`),
  Earn VP (`earn_vp_claims`), ad rewards (`ad_reward_claims`).
- First seen (`accounts.created_at`) and last authenticated activity
  (`accounts.last_seen_at`, touched on every authenticated request).
- Skins sold per item from `player_activity_events` (`SKINS_SOLD`), recorded
  in the same transaction as the sale from the date V75 is deployed onward.
  Before that, bulk sells wrote one wallet transaction without an item count,
  so only the number of sell operations is known historically.

Estimated (the client sends no heartbeat, logout, pause, resume or app-close
event, so these are server-side estimates and are flagged `is_estimated`):

- Sessions (`player_sessions`): authenticated activity within
  `valocase.analytics.session-timeout` (default 5 minutes) of the previous
  request continues the open session; a longer gap closes it with
  `end_reason = 'INACTIVITY_TIMEOUT'` and `ended_at` set to the last observed
  activity, and the next request starts a new session. No fake logout
  timestamp is ever written.
- Session duration = `last_activity_at - started_at`. Time played after the
  final request of a session is invisible to the server and is NOT counted;
  a single-request session has duration 0.
- Online status = last authenticated activity within the last 5 minutes.
  A player who force-closes the game can appear online for up to 5 minutes.
- Session-row updates are throttled (`valocase.analytics.write-throttle`,
  default 30s), so `last_activity_at` can lag real activity by up to ~1
  minute. Session tracking runs on a background thread after the request's
  transaction commits and never adds latency to or fails a gameplay request.
- App version and platform are NOT sent by the current client; the nullable
  `player_sessions.platform` / `app_version` columns stay empty until a
  future client provides them.

## Wallet transaction reasons (source of each VP change)

`STARTING_BALANCE`, `CASE_OPEN`, `INVENTORY_SELL`, `BATTLE_ENTRY`,
`BATTLE_LOBBY_ENTRY`, `BATTLE_LOBBY_REFUND`, `DAILY_REWARD`, `MISSION_REWARD`,
`EARN_VP`, `AD_REWARD_MARKET_VP`, `MARKET_VP_EXCHANGE`.

Battle and upgrade rewards are skins (inventory items), not VP; their VP value
is reported from `battle_rolls.vp_value` / `upgrades.target_value`. The
diamond balance has no ledger (only VP does); diamond changes are out of scope.

## Views (query these directly in DataGrip)

- `admin_user_analytics` - one row per user with all headline metrics
- `admin_current_online_users` - users active in the last 5 minutes
- `admin_recent_sessions` - estimated sessions with durations
- `admin_wallet_transaction_history` - full VP ledger with balance before/after
- `admin_battle_analytics` - per-user battle stats (lobby + bot battles,
  de-duplicated the same way as the leaderboard)
- `admin_upgrade_analytics` - per-user upgrade stats
- `admin_daily_players` - one row per player per day: name, session count,
  minutes played, whether they registered that day (added by V78)
- `admin_daily_summary` - one row per day: how many players, how many of them
  new, total and average minutes (added by V78)

The two daily views group by **Istanbul** day, not UTC, and attribute a session
entirely to the day it started — a session crossing midnight counts once, on its
starting day. Their `estimated_minutes` inherits the accuracy limits described
above (time after a player's last request is not counted);
`active_foreground_minutes` is exact but only for clients reporting the V76
session lifecycle.

Accounts are guest-only: there is no email anywhere in the system, so views
expose `username` (the display name). The system event account
(`00000000-0000-0000-0000-000000000001`, owns Free Lobby Events) is excluded.

Timestamps are stored as `timestamptz` (UTC-normalized, unchanged). To display
them in Istanbul time without altering stored values, run once per DataGrip
console: `SET TIME ZONE 'Europe/Istanbul';`

## Ready-to-run queries

```sql
-- 0. Display times in Istanbul (per console session; storage is unchanged).
SET TIME ZONE 'Europe/Istanbul';

-- 1. All users ordered by last activity.
SELECT * FROM admin_user_analytics ORDER BY last_activity_at DESC;

-- 2. Users currently considered online.
SELECT * FROM admin_current_online_users ORDER BY last_activity_at DESC;

-- 3. Current online-user count.
SELECT COUNT(*) AS online_users FROM admin_current_online_users;

-- 3b. Online count with a custom threshold (views use 5 minutes).
SELECT COUNT(*) FROM accounts
WHERE last_seen_at >= now() - INTERVAL '3 minutes'
  AND status = 'ACTIVE'
  AND id <> '00000000-0000-0000-0000-000000000001';

-- 4. A single user's complete analytics by username (no emails exist).
SELECT * FROM admin_user_analytics WHERE username = 'AgentAB12';

-- 5. Recent sessions (latest 100).
SELECT * FROM admin_recent_sessions ORDER BY started_at DESC LIMIT 100;

-- 6. Recent VP transactions (latest 200).
SELECT * FROM admin_wallet_transaction_history ORDER BY created_at DESC LIMIT 200;

-- 7. Total VP earned and spent per user.
SELECT user_id, username, total_vp_earned, total_vp_spent,
       vp_earn_tx_count, vp_spend_tx_count, current_vp_balance
FROM admin_user_analytics
ORDER BY total_vp_earned DESC;

-- 8. Battle statistics per user.
SELECT * FROM admin_battle_analytics
WHERE has_ever_battled
ORDER BY battles_joined DESC;

-- 9. Upgrade statistics per user.
SELECT * FROM admin_upgrade_analytics
WHERE has_ever_upgraded
ORDER BY upgrade_attempts DESC;

-- 9a. Today: who played, for how long (the everyday question).
SELECT username, level, session_count, estimated_minutes, is_new_user,
       first_session_at, last_activity_at
FROM admin_daily_players
WHERE day = (now() AT TIME ZONE 'Europe/Istanbul')::date
ORDER BY estimated_minutes DESC;

-- 9b. Today in one line: player count, new players, total minutes.
SELECT * FROM admin_daily_summary
WHERE day = (now() AT TIME ZONE 'Europe/Istanbul')::date;

-- 9c. Last 30 days, one row per day.
SELECT * FROM admin_daily_summary ORDER BY day DESC LIMIT 30;

-- 9d. One player's day-by-day history.
SELECT day, session_count, estimated_minutes
FROM admin_daily_players WHERE username = 'AgentAB12' ORDER BY day DESC;

-- 10. Daily active users (last 30 days, from authenticated sessions).
SELECT (started_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
       COUNT(DISTINCT account_id) AS dau
FROM player_sessions
WHERE started_at >= now() - INTERVAL '30 days'
GROUP BY day
ORDER BY day DESC;

-- 11. Average estimated session duration (sessions with any measurable length).
SELECT ROUND(AVG(estimated_minutes), 1) AS avg_session_minutes,
       COUNT(*) AS session_count
FROM admin_recent_sessions
WHERE NOT is_active;

-- 12. Gameplay activity during the 14-day closed-test window
--     (replace the range with the real test dates if needed).
WITH window_bounds AS (
    SELECT now() - INTERVAL '14 days' AS from_ts, now() AS to_ts
)
SELECT (t.created_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
       COUNT(*) FILTER (WHERE t.reason = 'CASE_OPEN')                          AS cases_opened,
       COUNT(*) FILTER (WHERE t.reason IN ('BATTLE_ENTRY','BATTLE_LOBBY_ENTRY')) AS battle_entries,
       COUNT(*) FILTER (WHERE t.reason = 'INVENTORY_SELL')                     AS sell_operations,
       COUNT(*) FILTER (WHERE t.reason IN
           ('DAILY_REWARD','MISSION_REWARD','EARN_VP','AD_REWARD_MARKET_VP'))  AS rewards_claimed,
       SUM(CASE WHEN t.delta > 0 THEN t.delta ELSE 0 END)                      AS vp_credited,
       SUM(CASE WHEN t.delta < 0 THEN -t.delta ELSE 0 END)                     AS vp_debited,
       COUNT(DISTINCT w.account_id)                                            AS active_wallets
FROM wallet_transactions t
JOIN wallets w ON w.id = t.wallet_id
JOIN window_bounds b ON t.created_at BETWEEN b.from_ts AND b.to_ts
GROUP BY day
ORDER BY day;
```

## Deployment

No manual schema step is needed: deploying the application applies V75
automatically through Flyway on startup (the existing deployment flow). V75 is
additive and idempotent (`IF NOT EXISTS` / `CREATE OR REPLACE VIEW`); it was
validated against PostgreSQL 16 populated with realistic pre-V75 gameplay data,
with row-level checksums proving no existing row or timestamp changed. The
production defaults (`session-timeout=PT5M`, `write-throttle=PT30S`) need no
environment variables.

Verification queries to run in DataGrip right after deployment:

```sql
SELECT version, description, success, installed_on
FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;

SELECT to_regclass('player_sessions')        IS NOT NULL AS sessions_table,
       to_regclass('player_activity_events') IS NOT NULL AS events_table;

SELECT table_name FROM information_schema.views
WHERE table_schema = 'public' AND table_name LIKE 'admin\_%' ESCAPE '\';

SELECT COUNT(*) FROM admin_user_analytics;

SELECT COUNT(*) FROM admin_wallet_transaction_history
WHERE balance_before <> balance_after - amount;  -- must be 0
```

Within minutes of players making requests, `player_sessions` starts filling and
`admin_current_online_users` shows active players.

## Precise client session lifecycle (V76)

`V76__client_session_lifecycle.sql` adds an opt-in protocol the Unity client uses
to report app foreground lifecycle precisely. It does not replace V75: sessions
with a `client_session_id` are precise; sessions without one remain the V75
request-estimated sessions and keep working. The server clock stays authoritative
for every stored timestamp and duration; the client's `clientSentAtUtc` is
diagnostic only and is never used in calculations.

### Endpoints (existing X-Guest-Token auth; the account comes only from the token)

- `POST /api/v1/analytics/session/start`
- `POST /api/v1/analytics/session/heartbeat`
- `POST /api/v1/analytics/session/pause`
- `POST /api/v1/analytics/session/resume`
- `POST /api/v1/analytics/session/end`

All five return the same compact body and never expose analytics to the player:
`{"serverSessionId": "<uuid|null>", "lifecycleState": "FOREGROUND|PAUSED|ENDED|NONE", "serverTimeUtc": "<iso-8601>"}`.
`NONE` means no live session matched (e.g. a heartbeat after timeout); the client
should send `start` again on the next foreground.

### Request fields and enums

- start and resume: `clientSessionId` (UUID), `installationId` (UUID),
  `appVersion` (string, ≤50), `platform` (`ANDROID|IOS|EDITOR|UNKNOWN`,
  case-insensitive; anything else stored as `UNKNOWN`), `clientSentAtUtc`
  (ISO-8601, diagnostic), `lifecycleSequence` (integer 1..1000000000).
- heartbeat and pause: `clientSessionId`, `clientSentAtUtc`, `lifecycleSequence`.
- end: `clientSessionId`, `clientSentAtUtc`, `lifecycleSequence`, `endReason`
  (`QUIT|LOGOUT`; blank/other → `UNKNOWN`). `REPLACED` and `INACTIVITY_TIMEOUT`
  are assigned by the server only and are never accepted from a client.

The client never supplies user id, account id, durations, login/logout times, VP,
gameplay counts or online status; those are ignored if sent.

### Lifecycle state machine

`start` opens a session in `FOREGROUND` with one open foreground segment.
`heartbeat` refreshes `last_heartbeat_at`/`last_activity_at`. `pause` closes the
open segment (reason `PAUSE`) and moves to `PAUSED`. `resume` opens a new
foreground segment and returns to `FOREGROUND`. `end` closes the open segment
(reason `END`) and the session (`ENDED`). If heartbeats simply stop, the timeout
scanner closes the session (`ENDED`).

### Timestamp, session and segment rules

Every timestamp is the server clock. Active foreground time is the sum of segment
durations only; the currently open segment is measured up to its last
server-observed heartbeat, never to `now()`, so no client exit time is invented.
Background time is the gap between consecutive segments within a session and is
excluded from foreground time. A graceful `end` sets `explicit_ended_at` and
`is_estimated = false`; a timeout close leaves `explicit_ended_at` null, sets
`is_estimated = true`, ends at the last observed activity, and uses
`end_reason = 'INACTIVITY_TIMEOUT'`. A `start` for an account that still has an
open session (a prior crash, or a request-estimated session) closes that one as
`REPLACED` first, preserving one open session per account.

### Production thresholds

Client heartbeat interval is 30 seconds. `valocase.analytics.heartbeat-timeout`
(default 2 minutes) is how long a session with no reported activity survives
before the scanner closes it as a timeout; the scanner runs every
`valocase.analytics.timeout-scan-interval` (default 30 seconds).
`valocase.analytics.heartbeat-write-throttle` (default 5 seconds) drops redundant
heartbeat writes. The views treat a foreground session with a heartbeat within
**90 seconds** as online (baked into the view SQL). A force-closed app therefore
stays online at most ~90 seconds, then is closed by the scanner within ~2 minutes.

### Idempotency and concurrency

One logical session per `(account_id, client_session_id)` and at most one open
foreground segment per session are enforced by partial unique indexes. Row locks
plus a bounded retry on unique collisions make duplicate start/heartbeat/pause/
resume/end and concurrent or multi-instance requests idempotent: 100 rapid
heartbeats and concurrent starts each collapse to a single session and segment. A
`lifecycleSequence` at or below the stored value is treated as stale and cannot
reverse newer state.

### What is now exact vs. still limited

Now exact from client lifecycle: app start, per-heartbeat presence, pause/resume,
graceful end, active foreground duration excluding background, current app
version, platform, installation id and client session id, and precise online
(foreground + recent heartbeat). Still not knowable: the exact instant of a
force-close, crash, battery kill or OS termination — those close by timeout at the
last observed heartbeat (`is_estimated = true`), and the client's own
`OnApplicationQuit` is best-effort, never guaranteed on Android/iOS.

### New / changed views

`admin_session_segments` and `admin_client_presence` are new. `admin_user_analytics`,
`admin_current_online_users` and `admin_recent_sessions` keep all their V75 columns
and gain lifecycle columns appended (V75 consumers are unaffected). Precise online
lives in `admin_client_presence.is_online` and the `is_client_online` column;
the V75 `is_currently_online` (last request within 5 minutes) is preserved.

### Unity integration (exact paths and sample payloads)

Generate a persistent random `installationId` (a plain `Guid`, stored in
PlayerPrefs/secure storage — never a hardware id), and a fresh `clientSessionId`
per app process. Increment `lifecycleSequence` monotonically from 1. After the
guest token is available:

Start / resume body:

```json
{
  "clientSessionId": "8f14e45f-ceea-467a-9575-1111aaaa2222",
  "installationId": "3d594650-3436-4f7e-9999-abcdef012345",
  "appVersion": "1.4.2",
  "platform": "ANDROID",
  "clientSentAtUtc": "2026-07-10T18:00:00Z",
  "lifecycleSequence": 1
}
```

Heartbeat / pause body:

```json
{ "clientSessionId": "8f14e45f-ceea-467a-9575-1111aaaa2222", "clientSentAtUtc": "2026-07-10T18:00:30Z", "lifecycleSequence": 2 }
```

End body:

```json
{ "clientSessionId": "8f14e45f-ceea-467a-9575-1111aaaa2222", "clientSentAtUtc": "2026-07-10T18:05:00Z", "lifecycleSequence": 9, "endReason": "QUIT" }
```

Send `start` once after auth; `heartbeat` every 30s only while foregrounded (use
unscaled realtime, no overlapping requests); `pause` on background and `resume` on
return; `end` best-effort on quit. Analytics failures must never surface to the
player or block gameplay.

### Lifecycle verification queries (DataGrip)

```sql
SET TIME ZONE 'Europe/Istanbul';

-- Currently online (precise: foreground + heartbeat within 90s).
SELECT * FROM admin_client_presence WHERE is_online ORDER BY last_heartbeat_at DESC;

-- Presence and app metadata for one user.
SELECT username, lifecycle_state, current_app_version, current_platform,
       current_installation_id, current_client_session_id,
       last_app_session_start, last_heartbeat_at, last_pause_at, last_resume_at,
       last_explicit_end_at, last_session_active_minutes,
       total_active_foreground_minutes, total_background_minutes
FROM admin_user_analytics WHERE username = 'HeavyPlayer';

-- Foreground segments (active minutes per interval) for recent sessions.
SELECT * FROM admin_session_segments ORDER BY started_at DESC LIMIT 100;

-- How the last session closed (EXPLICIT vs TIMEOUT vs OPEN) per user.
SELECT username, close_type, session_started_at, session_ended_at, is_estimated
FROM admin_client_presence ORDER BY session_started_at DESC;

-- A force-close shows as a TIMEOUT close with is_estimated true and no explicit end.
SELECT * FROM admin_recent_sessions
WHERE close_type = 'TIMEOUT' ORDER BY started_at DESC LIMIT 50;
```

## Rollback

Removes only the new analytics structures; no gameplay data is touched:

```sql
DROP VIEW IF EXISTS admin_user_analytics;
DROP VIEW IF EXISTS admin_current_online_users;
DROP VIEW IF EXISTS admin_recent_sessions;
DROP VIEW IF EXISTS admin_wallet_transaction_history;
DROP VIEW IF EXISTS admin_battle_analytics;
DROP VIEW IF EXISTS admin_upgrade_analytics;
DROP TABLE IF EXISTS player_activity_events;
DROP TABLE IF EXISTS player_sessions;
DROP INDEX IF EXISTS idx_accounts_last_seen_at;
DROP INDEX IF EXISTS idx_wallet_tx_wallet_created;
DROP INDEX IF EXISTS idx_wallet_tx_reason_created;
DROP INDEX IF EXISTS idx_wallet_tx_created;
DROP INDEX IF EXISTS idx_case_openings_account_created;
DROP INDEX IF EXISTS idx_battles_account_created;
DROP INDEX IF EXISTS idx_upgrades_account_created;
DROP INDEX IF EXISTS idx_battle_lobby_slots_account;
DELETE FROM flyway_schema_history WHERE version = '75';
```

(Reverting the Java changes means removing the `analytics` package,
`AnalyticsConfig`, the two `recordActivity` calls in `AccountService`, the two
`recordSkinsSold` calls in `InventoryService`, and the
`valocase.analytics.*` properties.)

### Reverting only V76 (keep V75)

Removes only the lifecycle structures and restores the three V75 view definitions;
V75 estimated sessions, all gameplay data, balances and timestamps are untouched.
Validated on PostgreSQL 16 against a populated copy.

```sql
DROP VIEW IF EXISTS admin_client_presence;
DROP VIEW IF EXISTS admin_session_segments;
DROP VIEW IF EXISTS admin_recent_sessions;
DROP VIEW IF EXISTS admin_current_online_users;
DROP VIEW IF EXISTS admin_user_analytics;
-- Recreate admin_user_analytics, admin_current_online_users and
-- admin_recent_sessions by running their exact definitions from
-- V75__player_activity_analytics.sql (the committed V75 view bodies).
DROP INDEX IF EXISTS uq_player_sessions_account_client;
DROP INDEX IF EXISTS idx_player_sessions_open_client;
DROP INDEX IF EXISTS uq_player_session_segments_open;
DROP INDEX IF EXISTS idx_player_session_segments_session;
DROP TABLE IF EXISTS player_session_segments;
ALTER TABLE player_sessions
    DROP COLUMN IF EXISTS client_session_id,
    DROP COLUMN IF EXISTS installation_id,
    DROP COLUMN IF EXISTS lifecycle_sequence,
    DROP COLUMN IF EXISTS lifecycle_state,
    DROP COLUMN IF EXISTS last_heartbeat_at,
    DROP COLUMN IF EXISTS explicit_ended_at;
DELETE FROM flyway_schema_history WHERE version = '76';
```

(Reverting the Java changes means removing the lifecycle DTOs/enums, the
`PlayerSessionSegment` entity and repository, `ClientSessionService`,
`AnalyticsSessionController`, `AnalyticsSessionTimeoutScheduler`, the
`PlayerSession` lifecycle fields, the `AccountService.resolveActiveAccount`
method, the client-session guard in `PlayerActivityService`, and the new
`valocase.analytics.heartbeat-*` / `timeout-scan-interval` properties.)
