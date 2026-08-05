# Installs but no new users — evidence-based investigation (revised)

**Date:** 2026-08-03
**Status:** Previous conclusion retracted. No code has been modified.

---

## Retraction

My earlier primary conclusion — that clients older than 1.0.18 cannot register, so
all paid installs fail — is **disproven** by your test. Production is 1.0.19, the
client sends `displayName`, and a fresh Play Store install created an account. The
`400`-on-empty-body path is real code, but it is not what your users are hitting.

That also changes the shape of the problem. You created a row in `accounts` today.
So the question is no longer "is registration broken" — it demonstrably is not for
at least one input. It is **"which subset of installs fails, or is the row present
and the report not showing it."** Those are two different defects and the evidence
below separates them.

Everything in this document is traced to a line of source. Where I cannot prove
something from this repository, I say so explicitly rather than infer it.

---

## The one question that splits the diagnosis

**Your report and your raw table do not agree with each other, by construction.**

| View | Source table | Does a brand-new account appear? |
|---|---|---|
| `admin_user_analytics` ([V76:255](../src/main/resources/db/migration/V76__client_session_lifecycle.sql#L255)) | `FROM accounts a` + LEFT JOINs | **Yes**, always |
| `admin_daily_players` ([V78:47-48](../src/main/resources/db/migration/V78__daily_player_analytics.sql#L47-L48)) | `FROM player_sessions s JOIN accounts a` | **Only if a `player_sessions` row exists** |
| `admin_daily_summary` ([V78:73](../src/main/resources/db/migration/V78__daily_player_analytics.sql#L73)) | `FROM admin_daily_players` | inherits the same restriction |

`admin_daily_players` is an **inner** join anchored on `player_sessions`. `new_player_count`
in `admin_daily_summary` ([V78:68](../src/main/resources/db/migration/V78__daily_player_analytics.sql#L68))
therefore counts *new accounts that have a session row*, not new accounts. An
account that registers and never gets a session row is **invisible in your daily
report while sitting in `accounts` the whole time.**

Those views landed in commit `f585642` on 2026-07-28 — one day before the registration
change, and squarely inside your problem window.

**Run this first. It settles which defect you have:**

```sql
SELECT (a.created_at AT TIME ZONE 'Europe/Istanbul')::date AS day,
       COUNT(*)                                            AS accounts_created,
       COUNT(s.account_id)                                 AS with_session_row
FROM accounts a
LEFT JOIN (SELECT DISTINCT account_id FROM player_sessions) s ON s.account_id = a.id
WHERE a.created_at > now() - INTERVAL '21 days'
GROUP BY 1 ORDER BY 1 DESC;
```

- `accounts_created` **> 0** and `with_session_row` **< accounts_created** → registration
  works; your dashboard is lying. Go to §3.
- `accounts_created` ≈ **0** → registration really is failing for most installs. Go to §2.

I cannot answer this from source code — it depends on production data I cannot reach.

---

## 1. Every code path where an install does NOT create an account

`new Account()` occurs **exactly once in the entire codebase**:
[AccountService.java:72](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L72).
There is no lazy creation, no fallback, no second writer, and **no `DELETE` of
`accounts` anywhere** in Java or in any of the 78 migrations (verified by grep).

So an install fails to produce an account if and only if `POST /api/v1/guest` does
not reach [AccountService.java:79](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L79)
(`accountRepository.save`). Ordered by where they cut in:

| # | Stop point | Result | Proven by |
|---|---|---|---|
| A | Client never sends the request (player never finishes the nickname screen) | no row | client-side; **not provable from this repo** |
| B | `Content-Type` not JSON | `500` | HTTP probe (§4) |
| C | Malformed JSON body | `500` | HTTP probe (§4) |
| D | `displayName` null/blank | `400` | [AccountService.java:113-115](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L113-L115) |
| E | length < 3 or > 20 | `400` | [AccountService.java:117-121](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L117-L121) |
| F | any character outside `[A-Za-z0-9_]` | `400` | [AccountService.java:122-125](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L122-L125) |
| G | DB unreachable / Flyway failure | `500` | infrastructure |

Path A is the one I want to flag as a boundary: **the Unity client is not in this
repository.** A Google Ads "install" is counted at install time, not at first
successful registration, and registration by design happens only after the player
completes the nickname screen ([AccountService.java:55-62](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L55-L62)).
Install-to-registration drop-off is therefore expected to be non-zero, and its size
cannot be determined from backend source.

## 2. Every condition that prevents POST /api/v1/guest from succeeding

Complete list — these are all of them, in execution order.

**Web layer** ([AccountController.java:39-44](../src/main/java/com/cenk/valocase/account/web/AccountController.java#L39-L44)):
1. `Content-Type` is not `application/json` → `HttpMediaTypeNotSupportedException`
2. body is not parseable JSON → `HttpMessageNotReadableException`
3. body absent → `request == null` → `displayName` passed as `null` → falls into 4

**Service layer** ([AccountService.registerGuest:68](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L68) → `requireValidDisplayName:112`):

4. [:113](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L113) — `rawDisplayName == null || isBlank()` → `400 "displayName is required"`
5. [:117](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L117) — `trimmed.length() < 3 || > 20` → `400`
6. [:122](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L122) — `!DISPLAY_NAME_PATTERN.matcher(trimmed).matches()` → `400`

There are **no other early returns and no other validations** on this path.
`registerGuest` has no conditional branches at all after validation — lines 70-93 are
straight-line code.

### The regex is the only condition that can fail *selectively*, and it is still live

[AccountService.java:39-40](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L39-L40):

```java
Pattern.compile("^[A-Za-z0-9_]+$")
```

This rejects spaces, dashes, apostrophes, and **every Turkish character** —
`ç ğ ı İ ö ş ü`. Your own test suite asserts this is intended:
[AccountRegistrationIT.java:131](../src/test/java/com/cenk/valocase/account/AccountRegistrationIT.java#L131)
requires `"çğüşiö"`, `"ad soyad"` and `"tire-li"` to all throw.

This is the only failure condition consistent with **every** fact you have given me:
it is partial (most installs fail, some succeed), it is invisible in logs (§4), and
**your successful test does not test it** — a Play Store install using an ASCII
nickname takes exactly the path that works. The product is Turkish-facing
(`"Oyuncu"` is the hardcoded fallback at
[AccountService.java:171](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L171)).

**What I can prove:** the rule rejects Turkish names, and nothing logs the rejection.
**What I cannot prove from source:** what fraction of your players type one, or
whether the client blocks them locally before sending. That requires the client or
production logs.

## 3. Every place an exception can be swallowed or genericised

| Location | Behaviour | Consequence |
|---|---|---|
| [GlobalExceptionHandler.java:42-46](../src/main/java/com/cenk/valocase/common/exception/GlobalExceptionHandler.java#L42-L46) | `@ExceptionHandler(Exception.class)` catch-all | Converts Spring MVC's own `4xx` exceptions into `500` — **proven in §4** |
| [GlobalExceptionHandler.java:37-40](../src/main/java/com/cenk/valocase/common/exception/GlobalExceptionHandler.java#L37-L40) | `handleApiException` logs **nothing** | Every `400`/`401`/`403` leaves zero trace |
| [PlayerActivityService.java:60](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L60) | `ThreadPoolExecutor.DiscardPolicy`, 1 thread, queue 1000 | Session writes **silently dropped** when saturated — no log line at all |
| [PlayerActivityService.java:119-125](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L119-L125) | `catch (DataIntegrityViolationException)` → fallback | Only `log.warn` if the fallback *also* fails |
| [PlayerActivityService.java:126-128](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L126-L128) | `catch (Exception e)` → `log.warn` | Any session-write failure degrades to a warning |
| [FreeLobbyEventScheduler.java:35](../src/main/java/com/cenk/valocase/battle/service/FreeLobbyEventScheduler.java#L35) | swallows `DataIntegrityViolationException` | Intentional (multi-instance race); unrelated to registration |
| [ClientSessionService.java:305-315](../src/main/java/com/cenk/valocase/analytics/service/ClientSessionService.java#L305-L315) | 3 retries then `409` | Session endpoints only |

The first three are the ones that matter. Together they mean: **a registration
rejection and a dropped session write are both completely silent**, and a client
contract error is reported as a server bug.

## 4. HTTP-layer behaviour — measured, not assumed

Run against the real `AccountController` with `@WebMvcTest` + mocked service
(probe file created, executed, then deleted — no code retained):

| Request shape | Actual status |
|---|---|
| `application/json` + `{"displayName":"Cenk"}` | `201` |
| `application/x-www-form-urlencoded` + empty body | **`500`** |
| no body, no `Content-Type` | reaches service → `400` in prod |
| `application/json` + `{}` | reaches service → `400` in prod |
| `application/json` + malformed JSON | **`500`** |

The `500`s are logged as `Unexpected server error` by
[GlobalExceptionHandler.java:44](../src/main/java/com/cenk/valocase/common/exception/GlobalExceptionHandler.java#L44).
Relevant to Unity because `UnityWebRequest.Post(url, "")` defaults to
`application/x-www-form-urlencoded`. Whether 1.0.19 sets the JSON content type
correctly cannot be determined from this repo — but your successful install proves
it does so on at least the path you exercised.

## 5. Everything in the first-launch request chain

**There is no filter or interceptor of any kind.** Verified: `spring-boot-starter-security`
is not a dependency (grep count 0 in `pom.xml`), and there are zero
`OncePerRequestFilter` / `HandlerInterceptor` / `jakarta.servlet.Filter` /
`FilterRegistrationBean` implementations in `src/main/java`.

The only request-scoped configuration is the CORS mapping
([WebCorsConfig.java:29-36](../src/main/java/com/cenk/valocase/config/WebCorsConfig.java#L29-L36)),
which is browser-enforced only and does not apply to native `UnityWebRequest`
([WebCorsConfig.java:9-16](../src/main/java/com/cenk/valocase/config/WebCorsConfig.java#L9-L16)).

Chain for registration:

```
DispatcherServlet
  └─ AccountController.registerGuest ......... AccountController.java:39
      └─ AccountService.registerGuest ........ AccountService.java:68
          ├─ requireValidDisplayName ......... AccountService.java:112   [can reject]
          ├─ AccountRepository.save .......... AccountService.java:79    [INSERT accounts]
          ├─ WalletService.createInitialWallet  WalletService.java:38    [INSERT wallets + wallet_transactions]
          └─ PlayerActivityService.recordActivity  AccountService.java:83 [ASYNC — see §7]
```

Endpoints a launching client may also call — none of which can create an account:
`GET /api/v1/health`, `GET /api/v1/skins|cases` (no auth),
`POST /api/v1/analytics/session/start`, `GET /api/v1/wallet`, `GET /api/v1/inventory`,
`GET /api/v1/daily`, `GET /api/v1/missions` (all require an existing `X-Guest-Token`).
**The exact call order is a client-side fact and is not provable from this repo.**

## 6. Country, IP, headers, User-Agent — proven irrelevant

Grepped the whole of `src/main/java` for `getRemoteAddr`, `X-Forwarded-For`,
`X-Real-IP`, `User-Agent`, `HttpServletRequest`, `getLocale`, `CF-IPCountry`, `geo`.

**Zero matches.** The only two `Locale` references are
`toUpperCase(Locale.ROOT)` in enum parsing
([ClientPlatform.java:16](../src/main/java/com/cenk/valocase/analytics/domain/ClientPlatform.java#L16),
[SessionEndReason.java:21](../src/main/java/com/cenk/valocase/analytics/domain/SessionEndReason.java#L21)).

The only `@RequestHeader` in the entire codebase is `X-Guest-Token`, and
`registerGuest` does not declare it. **Guest registration reads no headers at all.**
No IP filtering, no geo-blocking, no rate limiting, no User-Agent check exists in
this backend. Country cannot affect registration — *except indirectly, through which
characters a player types into the nickname field* (§2).

## 7. Feature flags, profiles, production-only behaviour

Complete inventory:

- `@ConditionalOnProperty`: **one**, on
  [CatalogImportRunner.java:20](../src/main/java/com/cenk/valocase/catalog/importer/CatalogImportRunner.java#L20)
  (`valocase.catalog.import-on-startup`, `false` in both `application.properties` and
  `application-prod.properties`).
- `@Profile`: **none**.
- `@Value`: **one**, the CORS origins in
  [WebCorsConfig.java:25](../src/main/java/com/cenk/valocase/config/WebCorsConfig.java#L25).
- `EnvironmentPostProcessor`: [RailwayDatabaseUrlPostProcessor](../src/main/java/com/cenk/valocase/config/RailwayDatabaseUrlPostProcessor.java),
  registered in `META-INF/spring.factories`. Rewrites `DATABASE_URL` to a JDBC URL;
  no-op if absent or already `jdbc:`.

**No flag, profile or environment variable gates account creation.** `dev` and `prod`
differ only in datasource, `show-sql`, and CORS origins.

One latent risk, not today's cause: `application-prod.properties:29` sets
`valocase.cors.allowed-origins=${CORS_ALLOWED_ORIGINS}` with **no default**, so an
unset variable fails startup. Your app is up, so it is set.

## 8. Async operations that make registration look successful while later failing

**This is the mechanism behind the reporting gap in §0.**

[AccountService.java:83](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L83)
calls `playerActivityService.recordActivity(accountId)` inside the registration
transaction. That call:

1. [PlayerActivityService.java:85-91](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L85-L91) — registers an `afterCommit` synchronisation
2. [:89](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L89) — hands the work to a **single-threaded** executor
3. [:118](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L118) — runs `upsertSession` in its **own** transaction, inserting the `player_sessions` row

The client gets its `201` and its token at step 0. Steps 1-3 happen afterwards, on
another thread, on another connection. **If step 2 or 3 fails, the account still
exists and the response was still `201` — but no `player_sessions` row is created,
and the account never appears in `admin_daily_players`.**

Two ways it fails silently:
- [:60](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L60) — `DiscardPolicy` drops the task with **no log** once 1000 tasks are queued behind one thread
- [:126-128](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L126-L128) — any other exception becomes a `log.warn` and is discarded

Also unbounded: `lastTracked` ([:52](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L52))
is a `ConcurrentHashMap` that is never evicted — it grows one entry per account
forever. Not a registration bug; worth knowing.

**On SQLState 23505 — it does not block new users.** I re-verified. The `23505`
caught at [:119](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L119)
(from `uq_player_sessions_open`,
[V75:37-38](../src/main/resources/db/migration/V75__player_activity_analytics.sql#L37-L38))
fires **after** the registration transaction has committed, on a different thread and
a different connection, so it cannot mark the caller rollback-only. Every V76
lifecycle column is nullable ([V76:16-22](../src/main/resources/db/migration/V76__client_session_lifecycle.sql#L16-L22),
matching [PlayerSession.java:60-76](../src/main/java/com/cenk/valocase/analytics/domain/PlayerSession.java#L60-L76)),
so the insert cannot fail on a not-null constraint either. 23505 in your logs is
session-tracking noise. It can, however, cost a `player_sessions` row — which is a
*reporting* failure, not a registration one.

## 9. Sequence diagram

```mermaid
sequenceDiagram
    participant U as Unity client
    participant D as DispatcherServlet<br/>(no filters, no security)
    participant C as AccountController:39
    participant S as AccountService:68
    participant V as requireValidDisplayName:112
    participant R as AccountRepository / WalletService:38
    participant P as PostgreSQL
    participant A as PlayerActivityService<br/>(async, 1 thread)

    U->>D: POST /api/v1/guest<br/>{"displayName":"..."}
    Note over D: 415 if Content-Type not JSON<br/>400-shaped error becomes 500
    D->>C: registerGuest(request)
    C->>S: registerGuest(displayName)

    S->>V: validate
    alt null / blank / <3 / >20 / non-ASCII
        V-->>C: ApiException 400
        C-->>U: 400 — NOTHING WRITTEN, NOTHING LOGGED
    else valid
        V-->>S: trimmed name
    end

    rect rgb(235, 245, 235)
        Note over S,P: one transaction
        S->>R: save(account)
        R->>P: INSERT accounts
        S->>R: createInitialWallet(STARTING_VP)
        R->>P: INSERT wallets + wallet_transactions
        S->>A: recordActivity() — registers afterCommit only
    end
    S-->>C: GuestRegisterResponse
    C-->>U: 201 + guestToken

    rect rgb(250, 240, 235)
        Note over A,P: AFTER commit, another thread, another transaction
        A->>A: executor.execute — DiscardPolicy drops silently if saturated
        A->>P: INSERT player_sessions
        Note over A,P: on failure: log.warn only.<br/>Account still exists; row missing from<br/>admin_daily_players (V78 is FROM player_sessions)
    end
```

## 10. Log statements to add

With these, one production install tells you exactly where the request stopped.
Ordered by diagnostic value.

**a. [GlobalExceptionHandler.java:37-40](../src/main/java/com/cenk/valocase/common/exception/GlobalExceptionHandler.java#L37-L40)** — the single highest-value change. Every `4xx` in the system is currently invisible.

```java
@ExceptionHandler(ApiException.class)
public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
    log.warn("API rejection {} — {}", ex.getStatus(), ex.getMessage());
    return build(ex.getStatus(), ex.getMessage(), ex.getCode());
}
```

**b. [AccountController.java:41](../src/main/java/com/cenk/valocase/account/web/AccountController.java#L41)** — add `@Slf4j` to the class; log arrival before delegating, so you can see requests that never reach the service.

```java
log.info("guest registration attempt: bodyPresent={} nameLength={}",
        request != null, request == null || request.displayName() == null
                ? -1 : request.displayName().length());
```

Log the **length, not the name** — a nickname is user-supplied PII.

**c. [AccountService.java:113, :117, :122](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L112-L125)** — distinguish *which* rule rejected. This is what tells you whether the regex is the problem:

```java
// :113
log.warn("guest registration rejected: reason=MISSING_NAME");
// :117
log.warn("guest registration rejected: reason=LENGTH len={}", trimmed.length());
// :122
log.warn("guest registration rejected: reason=CHARSET nonAscii={}",
        !trimmed.equals(trimmed.replaceAll("[^\\x00-\\x7F]", "")));
```

The `nonAscii` flag is the decisive signal for the Turkish-name hypothesis.

**d. [AccountService.java:85](../src/main/java/com/cenk/valocase/account/service/AccountService.java#L85)** — success, so accepted and rejected counts are comparable:

```java
log.info("guest registration created: accountId={}", account.getId());
```

**e. [PlayerActivityService.java:60](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L60)** — replace `DiscardPolicy` with a logging rejection handler; a silent drop is the reporting gap in §8:

```java
(runnable, ex) -> log.warn("activity tracking dropped — executor saturated, queue={}",
        ex.getQueue().size()),
```

**f. [PlayerActivityService.java:154](../src/main/java/com/cenk/valocase/analytics/service/PlayerActivityService.java#L154)** — confirm the session row is actually written:

```java
log.debug("session row created for account {}", accountId);
```

**g. `application-prod.properties`** — request-level visibility independent of app logging, so "zero traffic" and "all traffic rejected" stop looking identical:

```properties
server.tomcat.accesslog.enabled=true
server.tomcat.accesslog.directory=/dev
server.tomcat.accesslog.prefix=stdout
server.tomcat.accesslog.suffix=
server.tomcat.accesslog.pattern=%t "%r" %s %D
```

### Reading the result after one install

| Observation | Conclusion |
|---|---|
| no access-log line for `POST /api/v1/guest` | client never sent it — client-side or funnel drop-off |
| access log `500` | content-type / malformed JSON (§4) |
| `reason=CHARSET nonAscii=true` | **the regex is the cause** |
| `reason=MISSING_NAME` | client sent an empty body despite 1.0.19 |
| `registration created` but no `session row created` | registration fine — **reporting gap (§0, §8)** |
