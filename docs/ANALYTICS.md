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
