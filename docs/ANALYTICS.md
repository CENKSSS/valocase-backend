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
- App version and platform ARE sent, and have been since the first lifecycle
  client. `player_sessions.app_version` / `platform` are populated on every
  session a real client opens — production carries versions from `1.0.1`
  (2026-07-13) through `1.0.21`. The rows where they are empty are the
  zero-second `REPLACED` placeholders, not missing client data.

  *This bullet previously claimed the opposite. It was wrong from the day V76
  shipped, and is corrected here rather than quietly edited because it was read
  as evidence during the 2026-08-05 install-funnel investigation.*

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
- `admin_accounts_by_country` - total accounts per selected country, plus the
  `UNKNOWN` bucket of accounts that have none (added by V80)
- `admin_daily_new_accounts_by_country` - new accounts per country per Istanbul
  day (added by V80)
- `admin_sessions_by_country` - sessions and minutes per country per day, by the
  account's selected country (added by V80)
- `admin_onboarding_funnel_by_country` - the pre-account funnel split by country
  (added by V80)
- `admin_nickname_rejections_by_country` - which nickname rule blocks which
  country (added by V80)

### Country: what these numbers are, and are not

`accounts.country_code` is **self-reported**. It is the ISO-3166-1 alpha-2 code
the player picked from a list on the country screen. It is not derived from an
IP address, not from the store locale, not from the SIM, and nothing verifies
it. A player may deliberately pick a country they do not live in, and may change
it later from Settings. Read every country figure as a stated preference.

**Do not reconcile these against Google Ads (or any store) install reports by
country.** The ad network reports the country its own attribution assigns; this
column reports what the player tapped. There is no install-referrer, no
attribution id and no supported mechanism anywhere in this schema that links an
ad click to an account, so the two cannot be joined one-to-one and a difference
between them is not a discrepancy to chase.

`UNKNOWN` in these views means the country was never asked, not that it is
unknown: accounts created before the country screen shipped, and any created
during the migration window by a client that predates it. That bucket should
shrink after the client release and settle at a floor rather than reaching zero.

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

---

# Installation identity, durable telemetry and timezone-aware reporting

Added by `V82__account_installation_id.sql` and
`V83__timezone_aware_reporting.sql`, together with the Unity-side durable
telemetry queue. Written after the 2026-08-05 investigation, in which Google Ads
reported 43 installs and the backend could account for 3 app launches and 0
registrations.

## The canonical installation id

There is exactly one per device, and everything reports the same one.

- **Source:** `ClientIdentity.InstallationId` (Unity), a random `Guid` stored in
  `PlayerPrefs["valocase_installation_id"]`.
- **Lifecycle:** created on first read, persisted immediately, reused for the life
  of the install. It survives app restarts and store updates. It is cleared only
  when the OS clears the app data, which is also what a reinstall does â€” so a
  reinstall legitimately produces a new id.
- **Not derived from** an advertising id, a device id, an IMEI, a MAC address, or
  anything else identifying a person or a handset. It is a random UUID, nothing
  more.
- **Never travels beside** a nickname, a guest token, or an authorization header.

A second, independently generated id anywhere in the client would break every
join below without failing a build or a play test. `InstallationLinkPayloadTests`
pins this.

## Installation to account

Three tables carry the id. They answer different questions and all three are
needed:

| Table | Column | Type | Covers |
|---|---|---|---|
| `onboarding_events` | `installation_id` | `VARCHAR(64)` | Before an account exists |
| `accounts` | `installation_id` | `UUID` | The moment of registration (V82) |
| `player_sessions` | `installation_id` | `UUID` | Every authenticated session (V76) |

`onboarding_events` is text because that endpoint is unauthenticated and must
store whatever a broken client sends without failing the insert. The other two
are `UUID`. Use the `admin_installation_journey` view rather than casting by
hand â€” its guarded cast turns a non-UUID telemetry value into `NULL` instead of
raising `22P02`.

**`accounts.installation_id` is nullable and non-unique, permanently.**

- *Nullable*: clients older than the release that sends it â€” `1.0.19` and
  `1.0.21`, both live in the store â€” register exactly as before and leave it
  null. Nothing is ever back-filled.
- *Non-unique*: one installation legitimately registers several accounts.
  Production contained such cases before the column existed (one installation
  owns three accounts). A `UNIQUE` constraint would have refused those players.

The value is analytics data. It never authenticates, never authorises, and is
never returned to a client. A blank or unparseable value is dropped with a WARN
and the account is created anyway â€” a measurement may never cost a registration.

## Durable onboarding telemetry

The queue used to live only in memory, so the one case the funnel existed to
explain â€” a device that launches and dies â€” was the one case it could not record.

- **Storage:** `Application.persistentDataPath/onboarding_queue.json`.
- **Write:** temp file, then replace. A process killed mid-write leaves the
  previous queue intact rather than a truncated file that parses as zero events.
- **Coalesced:** at most one write per second, plus an immediate write on
  `OnApplicationPause`, `OnApplicationFocus(false)` and `OnApplicationQuit`. The
  pause hook is the one that matters on mobile â€” a swiped-away app never reaches
  `OnApplicationQuit`.
- **Bounded:** 32 in memory, 64 on disk. When full the *oldest* are dropped: a
  later funnel step implies the earlier ones happened.
- **Flushed:** on the next launch, ahead of anything that launch emits.
- **Corrupt file:** moved to `onboarding_queue.corrupt`, a warning is logged, and
  the queue starts empty. It is never read again â€” a file that cannot parse,
  retried every launch, would be a permanent error loop caused by telemetry.
- **Contents:** only the declared `OnboardingEventRequest` fields. No nickname, no
  guest token, no authorization header, no advertising id, no email, no IP. This
  file sits in plain text in the app sandbox, which is why that restriction is
  asserted by test rather than left to code review.

### Retry and deduplication

`eventId` is generated once, persisted with the event, and reused on every retry.
The backend `uq_onboarding_events_event_id` unique index is the enforcement
point, so a send that timed out after the server stored it does not double-count
the step. Retries are bounded (4 attempts, doubling from 2s) and only transient
failures are retried at all â€” a 400 or 404 is dropped, since resending an
identical body cannot produce a different answer.

### Error categories

`registration_failed` carries `networkErrorCategory` and `httpStatus`, mapped by
`BackendErrorMapper.NetworkCategory` onto the backend `NetworkErrorCategory`
allowlist: `offline`, `timeout`, `dns`, `transport`, `http_error`,
`invalid_response`, `unknown`. `httpStatus` is `0` when no HTTP response arrived.
No URL, hostname, exception message, stack trace or response body is ever
included. Nickname validation failures stay a separate vocabulary
(`rejection_reason`, from `RegistrationRejectionReason`) â€” a transport failure and
a refused nickname are different outcomes and must not share a bucket.

This path was already implemented before V82/V83. The all-null columns in
production were not a client gap: no registration failed in the window examined,
so there was nothing to record.

## Reporting timezone

**Storage is UTC and does not change.** Every timestamp is `TIMESTAMPTZ`. Only the
day boundary is a choice.

The V78/V79/V80 views cut the day at `Europe/Istanbul` and **keep doing so**. They
were not repointed: rewriting them would silently change every number already
read, and would be just as wrong the day a third country is added.

V83 adds functions taking the zone as an argument, validated against
`pg_timezone_names` so a typo raises instead of producing a report for the wrong
day:

```sql
SELECT * FROM admin_daily_summary_tz('Asia/Kolkata')    ORDER BY day DESC;
SELECT * FROM admin_daily_summary_tz('Europe/Istanbul') ORDER BY day DESC;
SELECT * FROM admin_daily_players_tz('Asia/Kolkata')    WHERE day = DATE '2026-08-05';
SELECT * FROM admin_onboarding_funnel_tz('Asia/Kolkata') ORDER BY day DESC;
```

The `Europe/Istanbul` output of `admin_daily_players_tz` was verified row for row
against the existing `admin_daily_players` view: 49 of 49 rows identical. The zone
changes which day a session lands on and nothing else â€” for 2026-08-04 the
Istanbul day shows 4 players and the Kolkata day 6.

Do **not** derive the reporting zone from `accounts.country_code`. The country is
self-reported, unverified, and null for most accounts; a report has to state which
day it means rather than infer it per row.

## Retention

Nothing here deletes anything automatically and no scheduled cleanup job is
added. `onboarding_events` grows by a handful of rows per install (the funnel is
eleven steps long) and was 104 kB at 16 rows. When it needs trimming, the raw
rows are the disposable part â€” every aggregate above is derivable from them:

```sql
-- Raw onboarding rows older than 60 days. Review the count before deleting.
SELECT COUNT(*) FROM onboarding_events WHERE received_at < NOW() - INTERVAL '60 days';
DELETE FROM onboarding_events        WHERE received_at < NOW() - INTERVAL '60 days';
```

## Known limitations

These are properties of server-side evidence, not gaps to be closed later:

- **The backend cannot prove an install that never launched.** A device that
  downloads the app and never opens it produces no request of any kind. That
  number exists only in Play Console.
- **A Google Ads install is not a backend app launch.** They count different
  events at different moments, attribution lags, and the two will never reconcile
  exactly. Comparing them is an order-of-magnitude check, nothing finer.
- **Anonymous endpoints leave no trace.** `/api/v1/health`, `/api/v1/skins`,
  `/api/v1/cases`, `/api/v1/market/catalog` and `/api/v1/leaderboards` write
  nothing anywhere. A device that launches, fetches the catalog and crashes is
  invisible unless telemetry reached the server.
- **Startup events stay best-effort until the first successful send.** The durable
  queue narrows the window to a crash before the first disk write (under one
  second), but cannot close it.
- **Clients older than the durable-queue release keep the old behaviour.** Events
  emitted by `1.0.19` and `1.0.21` and lost to a crash are gone; nothing
  retroactive is possible.
- **A registration refused before the install id is parsed is not correlated.**
  Nickname and country rejections log a reason code but no installation, so a
  refused registration cannot yet be joined to the funnel that led to it.

## Rollback

Both migrations are additive and reversible without data loss.

```sql
-- V83: reporting functions only. Nothing else references them.
DROP FUNCTION IF EXISTS admin_onboarding_funnel_tz(TEXT);
DROP FUNCTION IF EXISTS admin_daily_summary_tz(TEXT);
DROP FUNCTION IF EXISTS admin_daily_players_tz(TEXT);
DROP FUNCTION IF EXISTS admin_require_timezone(TEXT);
DELETE FROM flyway_schema_history WHERE version = '83';

-- V82: the view first, then the index, then the column.
DROP VIEW  IF EXISTS admin_installation_journey;
DROP INDEX IF EXISTS idx_accounts_installation_id;
ALTER TABLE accounts DROP COLUMN IF EXISTS installation_id;
DELETE FROM flyway_schema_history WHERE version = '82';
```

Reverting V82 also means removing `Account.installationId`, the `installationId`
component of `GuestRegisterRequest`, the three-argument
`AccountService.registerGuest` overload and `resolveInstallationId` â€” otherwise
`ddl-auto=validate` refuses to start. Reverting the client queue means deleting
`OnboardingTelemetryStore` and the persistence hooks in `OnboardingTelemetry`;
the in-memory queue then works exactly as it did before.

