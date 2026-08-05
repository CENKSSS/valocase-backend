# Guest registration — evidence-driven QA investigation

**Date:** 2026-08-03
**Client:** `C:\Users\cenk_\ValoCase`, Unity 6000.4.11f1, `bundleVersion 1.0.19`
**Backend:** this repo, `main` @ `97fc91a` + the diagnostics added earlier today
**Production behaviour:** unchanged. Nickname rules unchanged.

---

## What was actually executed, and what was not

Honesty about test provenance matters more than test count, so this is stated first.

| Harness | What ran | Status |
|---|---|---|
| **H1** — client nickname validator, 24 inputs | `TryValidateNickname` copied **verbatim** from `FirstLaunchProfilePopup.cs:204-220` into a .NET 10 console probe. Non-ASCII inputs written as `\u` escapes so file encoding cannot affect the result. | **RUN** |
| **H2** — client error mapper, 10 failure modes | `BackendErrorMapper.Map` copied **verbatim** from `BackendErrorMapper.cs:28-51`, plus the `BackendError` members from `BackendApiClient.cs:503-566`. | **RUN** |
| **H3** — backend HTTP stack, 19 request shapes | Real `AccountController`, real `AccountService` validation, real `GlobalExceptionHandler` via standalone `MockMvc`. Only `AccountRepository`/`WalletService` mocked. | **RUN** |
| **H4** — permanent regression tests | 32 tests added earlier today. | **RUN, all pass** |
| **H5** — DB row creation (`accounts`, `player_sessions`) | Requires PostgreSQL. Local service `postgresql-x64-16` is **Stopped and Disabled**; enabling a service you deliberately disabled needs admin rights and is not mine to do silently. | **NOT RUN** — manual steps in Part 3 |
| **H6** — Unity runtime / device funnel | The project has **no test assemblies** (`find Assets -name "*.asmdef"` → empty), so there is no EditMode suite to run, and adding one would modify the Unity project. | **NOT RUN** — manual steps in Part 2 |

Both temporary probes were deleted after recording results. `git status` confirms
only the intended files remain.

**The limit of H1/H2:** they prove the *logic* is byte-identical to production, because
the code is copied verbatim. They do **not** exercise `TMP_InputField` — so what a real
Turkish keyboard, autocorrect, predictive text or IME actually *delivers* to the
validator is untested. That gap needs a device (Part 2, S-DEV).

---

# Part 1 — Nickname test matrix

## 1a. Unity client validation (H1, executed)

`TYPABLE` reflects `characterLimit = 20` at `FirstLaunchProfilePopup.cs:363`, which
truncates typed *and* pasted input.

| ID | Input | Len | Typable | Unity | Branch | Player message | POST sent? |
|---|---|---|---|---|---|---|---|
| T01 | `Player123` | 9 | yes | **ACCEPT** | `:219` | — | **yes** |
| T02 | `Cinar` | 5 | yes | **ACCEPT** | `:219` | — | **yes** |
| T03 | `Çınar` | 5 | yes | **REJECT** | `:213-218` U+00C7 | *Only letters, digits and _ are allowed.* | no |
| T04 | `Yigit` | 5 | yes | **ACCEPT** | `:219` | — | **yes** |
| T05 | `Yiğit` | 5 | yes | **REJECT** | `:213-218` U+011F | *Only letters, digits and _ are allowed.* | no |
| T06 | `Ahmet` | 5 | yes | **ACCEPT** | `:219` | — | **yes** |
| T07 | `Ahmet Yilmaz` | 12 | yes | **REJECT** | `:213-218` **U+0020** | *Only letters, digits and _ are allowed.* | no |
| T08 | `Ahmet Yılmaz` | 12 | yes | **REJECT** | `:213-218` U+0020 | *Only letters, digits and _ are allowed.* | no |
| T09 | `محمد` (Arabic) | 4 | yes | **REJECT** | `:213-218` U+0645 | *Only letters, digits and _ are allowed.* | no |
| T10 | `अर्जुन` (Hindi) | 6 | yes | **REJECT** | `:213-218` U+0905 | *Only letters, digits and _ are allowed.* | no |
| T11 | `player_name` | 11 | yes | **ACCEPT** | `:219` | — | **yes** |
| T12 | `ab` | 2 | yes | **REJECT** | `:210` | *Nickname must be at least 3 characters.* | no |
| T13 | 20×`A` | 20 | yes | **ACCEPT** | `:219` | — | **yes** |
| T14 | 21×`A` | 21 | **NO** | **REJECT** | `:211` | *Nickname must be at most 20 characters.* | no |
| T15 | blank | 0 | yes | **REJECT** | `:209` | *Please enter a nickname.* | no |
| T16 | `"   "` | 3 | yes | **REJECT** | `:209` | *Please enter a nickname.* | no |
| T17 | `"  Cenk  "` | 8 | yes | **ACCEPT** | `:219` | — | **yes**, trimmed to `Cenk` |
| T18 | `Cenk😀` | 6 | yes | **REJECT** | `:213-218` U+D83D | *Only letters, digits and _ are allowed.* | no |
| T19 | `Cenk!` | 5 | yes | **REJECT** | `:213-218` U+0021 | *Only letters, digits and _ are allowed.* | no |
| T20 | `Player123` (dup) | 9 | yes | **ACCEPT** | `:219` | — | **yes** |
| T21 | null | — | yes | **REJECT** | `:209` | *Please enter a nickname.* | no |
| T22 | `علی` (Urdu) | 3 | yes | **REJECT** | `:213-218` U+0639 | *Only letters, digits and _ are allowed.* | no |
| T23 | `Mehmet-53` | 9 | yes | **REJECT** | `:213-218` U+002D | *Only letters, digits and _ are allowed.* | no |
| T24 | `O’Brien` | 7 | yes | **REJECT** | `:213-218` U+2019 | *Only letters, digits and _ are allowed.* | no |

**Executed totals: 8 accepted (request sent), 16 blocked (no request at all).**
Probe also proved: **every accepted nickname is pure ASCII**, so the JSON body can never
contain an escape or multi-byte sequence — encoding is a non-issue on the wire.

**T14 is unreachable through the UI.** `characterLimit = 20` means a 21-character name
cannot be typed or pasted, so branch `:211` is dead code in practice.

## 1b. Backend response for each body (H3, executed)

Real controller + real validation + real exception handler.

| ID | Body | HTTP | Server message | Reason code |
|---|---|---|---|---|
| B01 | `{"displayName":"Player123"}` | **201** | — | — |
| B02 | `{"displayName":"Cinar"}` | **201** | — | — |
| B03 | `{"displayName":"Yigit"}` | **201** | — | — |
| B04 | `{"displayName":"Ahmet"}` | **201** | — | — |
| B05 | `{"displayName":"player_name"}` | **201** | — | — |
| B06 | 20×`A` | **201** | — | — |
| B07 | `{"displayName":"  Cenk  "}` | **201** | stored as `Cenk` | — |
| B08 | `Player123` **again** | **201** | **second account created** | — |
| B09 | `Çınar` sent directly | 400 | `displayName may only contain letters, numbers and underscore` | INVALID_CHARSET |
| B10 | `محمد` sent directly | 400 | same | INVALID_CHARSET |
| B11 | `Ahmet Yilmaz` sent directly | 400 | same | INVALID_CHARSET |
| B12 | `ab` | 400 | `displayName must be between 3 and 20 characters` | TOO_SHORT |
| B13 | 21×`A` | 400 | same message | TOO_LONG |
| B14 | `""` | 400 | `displayName is required` | BLANK |
| B15 | `"   "` | 400 | `displayName is required` | BLANK |
| B16 | `{}` | 400 | `displayName is required` | BLANK |
| B17 | `{"displayName":null}` | 400 | `displayName is required` | BLANK |
| B18 | no body | 400 | `displayName is required` | BLANK |
| B19 | form-encoded | **500** | `Unexpected server error` | — |

Counters after the run, proving the new diagnostics work through the real stack:

```
guest_registration_started=17 guest_registration_success=8 guest_registration_rejected=9
session_creation_success=0 session_creation_failed=0 session_task_discarded=0
rejections={BLANK=4, INVALID_CHARSET=3, TOO_LONG=1, TOO_SHORT=1}
```

## 1c. Consolidated answers to the 10 questions

| # | Question | Answer (evidence) |
|---|---|---|
| 1 | Unity accept/reject | Table 1a — 8 accept, 16 reject |
| 2 | Validation branch | Table 1a, `FirstLaunchProfilePopup.cs:209/210/211/213-218` |
| 3 | Player message | Table 1a — **all four messages are in English** (see D-01) |
| 4 | Is POST called? | Only for the 8 accepted. **The other 16 never open a socket.** |
| 5 | JSON body | `{"displayName":"<trimmed>"}` via `JsonUtility.ToJson`, `BackendApiClient.cs:63-66`. Always pure ASCII. |
| 6 | HTTP response | Table 1b — all 8 client-accepted names → **201** |
| 7 | Account row created? | **NOT VERIFIED** — H5 not run. Service-level insert is proven by `AccountRegistrationIT`; real row needs Postgres. |
| 8 | `player_session` row? | **NOT VERIFIED** — H5 not run. Path proven statically (`AccountService.java:83` → async). |
| 9 | UI recovers? | **Yes.** `SetSaving(false)` at `:157`, `:163`, `:191`; error clears on valid input at `:251-255`. Retry always possible. |
| 10 | Release-build logs? | **Almost none.** Every diagnostic in `Send` is behind `#if UNITY_EDITOR \|\| DEVELOPMENT_BUILD` (`BackendApiClient.cs:340, 348, 395, 407, 423, 440`). **Client-side validation rejection logs nothing at all.** |

**Duplicate nicknames are allowed** — B08 returned 201 with a distinct `accountId`, and
no unique constraint on `display_name` exists in any of the 78 migrations. `DUPLICATE`
is therefore an unreachable reason code today.

---

# Part 2 — First-launch funnel scenarios

## 2a. Failure-mode messages (H2, executed)

| ID | Scenario | Request sent? | Message the player sees (Turkish) |
|---|---|---|---|
| S06 | Airplane mode before Continue | **NO** | *İnternet bağlantısı yok. Lütfen bağlantını kontrol et.* |
| S07 | Internet lost mid-request | yes | *İnternet bağlantısı yok…* |
| S08 | DNS failure | yes | *İşlem başarısız. Lütfen tekrar dene.* |
| S09 | Timeout (15 s) | yes | *Sunucu yanıt vermedi. Lütfen tekrar dene.* |
| S10 | HTTP 400 | yes | *İşlem başarısız. Lütfen tekrar dene.* |
| S11 | HTTP 409 | yes | ***İşlem tamamlanamadı. VP bakiyeni veya mevcut durumunu kontrol et.*** |
| S12 | HTTP 500 | yes | *Sunucu hatası oluştu. Lütfen biraz sonra tekrar dene.* |
| S12b | HTTP 503 | yes | *Sunucu hatası oluştu…* |
| S12c | 2xx unparseable body | yes | *İşlem başarısız. Lütfen tekrar dene.* |
| S12d | null error object | yes | *Beklenmeyen bir hata oluştu. Lütfen tekrar dene.* |

S11 tells a player who has **no account and no wallet** to check their VP balance
(`BackendErrorMapper.cs:17, 45`). Nonsensical in this context.

## 2b. Scenario-by-scenario verdict

Legend: **Und.** = player understands what happened · **Retry** = can retry ·
**NoAcct** = can reach gameplay without an account · **Skip** = nickname screen can
become permanently skipped · **BadState** = incorrect state persisted ·
**≡Abandon** = indistinguishable from voluntary abandonment, backend-side

| # | Scenario | Und. | Retry | NoAcct | Skip | BadState | ≡Abandon | DB rows |
|---|---|---|---|---|---|---|---|---|
| 1 | Fresh install → accept notice → valid name → Continue | yes | n/a | no | no | no | no | account + session |
| 2 | Close app at fan notice | n/a | yes¹ | no | no | no | **YES** | none |
| 3 | Close app at nickname screen | n/a | yes¹ | no | no | no | **YES** | none |
| 4 | `Yiğit` rejected → corrected to `Yigit` → retry | **partly²** | yes | no | no | no | — | account + session |
| 5 | Continue pressed rapidly | yes | yes | no | no | no | no | **one** account³ |
| 6 | Internet off before Continue | yes | yes | no | no | no | **YES** | none |
| 7 | Internet lost mid-request | yes | yes | no | no | **see A-02** | **YES** | possibly account⁴ |
| 8 | DNS failure | **no**⁵ | yes | no | no | no | **YES** | none |
| 9 | Timeout | yes | yes | no | no | no | **YES** | possibly account⁴ |
| 10 | HTTP 400 | **no**⁵ | yes | no | no | no | no | none |
| 11 | HTTP 409 | **no**⁶ | yes | no | no | no | no | none |
| 12 | HTTP 500 | yes | yes | no | no | no | no | none |
| 13 | App closed mid-request | n/a | yes¹ | no | no | no | **YES** | possibly account⁴ |
| 14 | Restart after any failure | yes | yes | no | **no**⁷ | no | — | — |
| 15 | Canvas/`SafeArea` missing | **no** | **NO** | **YES** | session only | no | **YES** | none |

¹ Popup re-shows next launch: `profileSetupCompleted` is set only in
`MarkCompleteAndClose` (`FirstLaunchProfilePopup.cs:197`), after success.
² The rejection reason is shown **in English** — see D-01.
³ Guarded twice: `_saving` at `:125`, `_confirmBtn.interactable = !_saving` at `:243`.
⁴ Account may commit server-side while the client sees failure. On next launch
`Save.Data.guestToken` is empty, so the player registers again → **orphan account**.
⁵ Mapped to the generic *"İşlem başarısız"* — indistinguishable from any other failure.
⁶ Message refers to VP balance, which does not exist yet.
⁷ Verified: `PlayerProfileData.Initialize` (`PlayerProfileData.cs:53-83`) only **reads**
PlayerPrefs; it never writes a default avatar key, so the `IsSetupComplete` legacy gate
at `:66-67` stays false. `SaveModels.cs:85` defaults `playerName = "Agent"`, which the
same gate explicitly excludes. **The nickname screen cannot be permanently skipped by
save state.** Only scenario 15 skips it, and only for that session.

## 2c. Device tests I could not run — exact manual steps

**S-DEV-1 — Turkish keyboard reality check** (the highest-value untested item)

1. Android phone, Turkish system language + Turkish keyboard.
2. Uninstall ValoCase; install 1.0.19 from Play.
3. Launch → accept the fan-made notice → nickname field.
4. Type `Yiğit` using the Turkish keyboard. Do **not** paste.
5. Record: does the `ğ` appear in the field at all? Does autocorrect alter it?
6. Press CONFIRM. Record the exact on-screen message.
7. Repeat with `Ahmet Yılmaz` (with the space).

Purpose: H1 proves the validator's verdict, not what `TMP_InputField` delivers to it.

**S-DEV-2 — funnel drop-off with real users**

1. `adb logcat -s Unity` while a naive test user installs and launches.
2. Record which of these appear:
   `[FanMadeNotice] Popup shown`, `[FirstLaunchProfile] Popup shown`,
   `[BackendAuth] Guest registered on nickname confirm`, `[FirstLaunchProfile] Setup complete`.
3. The last line reached is the drop-off point.

These four lines are **not** behind `#if UNITY_EDITOR` (`FanMadeNoticePopup.cs:63`,
`FirstLaunchProfilePopup.cs:106, 199`, `GameContext.cs:336`), so they **do** appear in
the Play Store build. This is the only funnel telemetry that exists today, and it is
only readable over USB.

**S-DEV-3 — scenario 15 (missing Canvas)** — do **not** attempt on production. It
requires removing the `SafeArea` object from the Main scene in the editor and entering
Play mode. Editor-only.

---

# Part 3 — Client/backend correlation

## 3a. Correlated results (H1 → H3)

Nicknames are classified, never logged, per your constraint. No guest tokens appear
anywhere in this document.

| Test | Nickname class | Unity sends? | Backend receives | Validation | HTTP | Account | Session |
|---|---|---|---|---|---|---|---|
| T01/B01 | ascii-alnum | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T02/B02 | ascii-alpha | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T04/B03 | ascii-alpha | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T06/B04 | ascii-alpha | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T11/B05 | ascii-underscore | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T13/B06 | ascii-maxlen | yes | yes | pass | 201 | created¹ | NOT VERIFIED |
| T17/B07 | ascii-padded | yes | yes | pass (trimmed) | 201 | created¹ | NOT VERIFIED |
| T20/B08 | ascii-duplicate | yes | yes | pass | 201 | **2nd account** | NOT VERIFIED |
| T03 | turkish-diacritic | **NO** | **never** | n/a | n/a | none | none |
| T05 | turkish-diacritic | **NO** | **never** | n/a | n/a | none | none |
| T07 | ascii-with-space | **NO** | **never** | n/a | n/a | none | none |
| T08 | turkish-with-space | **NO** | **never** | n/a | n/a | none | none |
| T09 | arabic | **NO** | **never** | n/a | n/a | none | none |
| T10 | devanagari | **NO** | **never** | n/a | n/a | none | none |
| T22 | urdu-arabic | **NO** | **never** | n/a | n/a | none | none |
| T23 | ascii-hyphen | **NO** | **never** | n/a | n/a | none | none |
| T24 | unicode-apostrophe | **NO** | **never** | n/a | n/a | none | none |
| T12 | too-short | **NO** | **never** | n/a | n/a | none | none |
| T15/T16/T21 | empty | **NO** | **never** | n/a | n/a | none | none |
| T18 | emoji | **NO** | **never** | n/a | n/a | none | none |
| T19 | punctuation | **NO** | **never** | n/a | n/a | none | none |

¹ Mocked repository — the service was called and returned a `201` envelope. Row
persistence is H5, not run.

**Installation ID is not available for correlation.** `installationId` is generated only
for the analytics session protocol (`AnalyticsLifecycleService`), which requires an
existing `X-Guest-Token`. At registration time there is no installation identifier on
either side, so a failed registration **cannot be correlated to a device at all**. This
is the structural reason the funnel is unmeasurable — see D-03.

## 3b. Running H5 yourself (unlocks the last two columns)

```bash
powershell -Command "Set-Service postgresql-x64-16 -StartupType Manual; Start-Service postgresql-x64-16"
```

```bash
./mvnw -o test -Dtest=AccountRegistrationIT -DfailIfNoTests=false
```

Then, for the live end-to-end correlation against a local backend:

```bash
./mvnw -o spring-boot:run -Dspring-boot.run.profiles=dev
```

Point `Assets/_ValoCase/Resources/GameConfig.asset` → `backendBaseUrl` at
`http://10.0.2.2:8080` for an emulator (or your LAN IP for a device), rebuild, and run
S-DEV-1. **Revert `backendBaseUrl` before any production build.**

---

# Part 4 — Findings classified

## A. Proven technical defect

**A-01 — Form-encoded or malformed body returns 500 instead of 4xx**
*Evidence:* B19 executed → HTTP 500 `Unexpected server error`.
*Repro:* `POST /api/v1/guest` with `Content-Type: application/x-www-form-urlencoded`.
*File:* `GlobalExceptionHandler.java:42-46`.
*Severity:* Low-Medium. *Explains 14/0?* **No** — 1.0.19 sets JSON correctly
(`BackendApiClient.cs:366`), proven by your successful install. *Confidence:* High.

**A-02 — Ambiguous timeout creates orphan accounts**
*Evidence:* `RegisterGuestRoutine` has no retry and no reconciliation; on transport
failure `registered` stays false and `onFailed` fires, while the server may have
committed. Next launch has no token, so the player registers again.
*Repro:* Scenarios 7, 9, 13.
*Files:* `GameContext.cs:301-344`; token discard path at `:308-316`.
*Severity:* Medium. *Explains 14/0?* **No** — it produces *extra* accounts, not zero.
*Confidence:* High (code path), Medium (real-world frequency).

**A-03 — HTTP 409 shows a VP-balance message during registration**
*Evidence:* S11 executed → *"İşlem tamamlanamadı. VP bakiyeni… kontrol et."*
*File:* `BackendErrorMapper.cs:17, 45`.
*Severity:* Low. *Explains 14/0?* **No** — the backend never returns 409 here.
*Confidence:* High.

**A-04 — Missing Canvas silently skips registration**
*Evidence:* `FindPopupParent()` returns null → `LogWarning` → popup destroyed, gameplay
continues with no account.
*File:* `FirstLaunchProfilePopup.cs:94-100`.
*Severity:* High if it fires. *Explains 14/0?* **Possibly, but unevidenced** — your own
1.0.19 install found the Canvas, so it is not universal. *Confidence:* High that the
path exists; **no evidence it fires in production**.

## B. Poor UX that can cause abandonment

**B-01 — Rejection messages are in English in a Turkish-language app** ← *strongest new finding*
*Evidence:* All four validator messages are English string literals
(`FirstLaunchProfilePopup.cs:209, 210, 211, 217`), while every network error is Turkish
(`BackendErrorMapper.cs:13-22`). A Turkish player typing `Yiğit` sees
*"Only letters, digits and _ are allowed."*
*Severity:* **High.** *Explains 14/0?* **Yes — this is the single most plausible
contributor.** *Confidence:* High that the text is English; Medium that it drives the
observed drop-off.

**B-02 — Ordinary names are rejected, including plain ASCII ones**
*Evidence:* T03, T05, T07, T08, T09, T10, T22, T23, T24 all blocked. **T07
`Ahmet Yilmaz` contains no Turkish character at all** — the space alone (U+0020) is
enough. Spaces, hyphens and apostrophes are normal in names in every market.
*File:* `FirstLaunchProfilePopup.cs:213-218`.
*Severity:* **High.** *Explains 14/0?* **Contributes.** Not a hard block — the player
*can* retype — so it is friction, not a defect. *Confidence:* High.

**B-03 — Two consecutive modal gates before any gameplay**
*Evidence:* Fan-made legal notice (`FanMadeNoticePopup.cs:32`) must be accepted before
the nickname screen (`:35`, `:83`). Neither has a back handler or skip.
*Severity:* Medium. *Explains 14/0?* **Contributes.** *Confidence:* High.

**B-04 — Generic message for DNS failure and HTTP 400**
*Evidence:* S08, S10 → *"İşlem başarısız. Lütfen tekrar dene."*
*File:* `BackendErrorMapper.cs:35, 49`. *Severity:* Low. *Confidence:* High.

## C. Expected user abandonment

**C-01 — Install without launch.** Google Ads counts installs; registration needs a
launch plus two modals plus a valid nickname. *Explains 14/0?* **Yes, partially.**
*Confidence:* High that it happens; **zero** measurement of magnitude.

**C-02 — Quitting at either modal.** Scenarios 2 and 3. Correct behaviour: no account,
popup re-shows next launch. *Confidence:* High.

## D. Measurement blind spots

**D-01 — Client-side rejection produces no signal anywhere.**
`TryValidateNickname` returns false, `ShowError` writes a UI label
(`FirstLaunchProfilePopup.cs:222-225`), and nothing is logged or sent. **The backend
counters I added earlier today cannot see this** — `INVALID_CHARSET` will read zero no
matter how many players are blocked, because no request is made.
*Severity:* **Critical for diagnosis.** *Confidence:* High.

**D-02 — Release builds log almost nothing about failures.**
Six diagnostics in `Send` are behind `#if UNITY_EDITOR || DEVELOPMENT_BUILD`
(`BackendApiClient.cs:340, 348, 395, 407, 423, 440`). *Confidence:* High.

**D-03 — No pre-account device identity.** Without an installation id before
registration, a failed attempt cannot be attributed to a device or counted.
*Confidence:* High.

**D-04 — Four distinct outcomes are backend-identical.** Never launched, quit at the
notice, quit at the nickname screen, blocked by the charset rule — all produce
`guest_registration_started=0`. *Confidence:* High.

## E. Unproven hypotheses

**E-01 — Turkish nicknames are the dominant cause.** Plausible and consistent with all
evidence, but the blocking rate in production is unmeasured. *Confidence:* Medium.
**E-02 — Ad traffic quality.** 14 installs with 0 launches is possible (incentivised or
fraudulent traffic) and completely untested from here. *Confidence:* Low.
**E-03 — A-04 firing in production.** No evidence. *Confidence:* Low.

## What 0-of-14 actually tells us

If each install completes registration with probability *p*, then P(0 of 14) = (1−*p*)¹⁴:

| *p* | P(0 of 14) |
|---|---|
| 0.05 | 49% |
| 0.15 | 10% |
| 0.30 | 0.7% |
| 0.50 | 0.006% |

So 0/14 is **unremarkable** if the funnel converts ≤15%, and **strong evidence of a
defect** only if it should convert ≥30%. With two modals, an English-language rejection
message, and a charset rule that blocks ordinary names, a sub-15% conversion is entirely
credible **without any technical defect at all**. 14 is too small a sample to separate
the two — which is exactly why telemetry, not more analysis, is the next step.

---

# Part 5 — Final diagnosis

**1. Can a valid user press Continue and still fail without understanding why?**
**Yes.** DNS failure and HTTP 400 both render *"İşlem başarısız. Lütfen tekrar dene."*
(S08, S10 executed) — no indication of cause. HTTP 409 is worse: it blames a VP balance
that does not exist yet (A-03).

**2. Can a user be blocked before any backend request is sent?**
**Yes — proven.** 16 of 24 matrix inputs never open a socket
(`FirstLaunchProfilePopup.cs:129-133`). Also airplane mode (S06) aborts at
`GameContext.cs:295` before the transport layer.

**3. Can the app enter gameplay without creating an account?**
**Yes**, via one path: Canvas/`SafeArea` missing → `FirstLaunchProfilePopup.cs:94-100`.
The local-economy branch at `:168-171` cannot fire in a Play build
(`GameContext.cs:53-63`). No evidence the Canvas path occurs in production.

**4. Can a temporary network error permanently prevent registration?**
**No.** Every failure calls `SetSaving(false)` (`:157`, `:163`, `:191`), nothing is
persisted, and the popup re-shows next launch (verified: `PlayerProfileData.Initialize`
only reads PlayerPrefs; `playerName` defaults to `"Agent"`). The one lasting artefact is
A-02's orphan account, which is a server-side duplicate, not a block.

**5. Can a Turkish, Arabic, Hindi, Urdu, or space-containing nickname prevent registration?**
**It blocks that attempt — proven for all five** (T03/T05/T08, T09, T10, T22, T07/T08).
**It does not prevent registration permanently**: the player can retype in ASCII and
succeed. Whether they do is the open question. Note **T07 `Ahmet Yilmaz` is pure ASCII**
— the space alone is disqualifying, so this is not only a non-Latin-script problem.

**6. Does the UI let the user correct the nickname and retry successfully?**
**Yes.** `OnNicknameChanged` (`:251-255`) clears the error as soon as the name becomes
valid, the CONFIRM button re-colours (`:238-247`), and scenario 4 succeeds. The
mechanism is sound; the *message* is in the wrong language (B-01).

**7. Is there evidence that all 14 users failed technically?**
**No.** No telemetry exists for any pre-request failure (D-01, D-03), so there is no
evidence either way. I will not claim it.

**8. Is there evidence that all 14 simply chose not to continue?**
**No — equally unevidenced.** The arithmetic above shows 0/14 is compatible with both.
Anyone asserting either answer today is guessing.

**9. Smallest telemetry set that separates technical failure from abandonment**

Four events, one unauthenticated endpoint, no account required:

| Event | Fire at | Distinguishes |
|---|---|---|
| `app_launched` | `GameContext.cs:197` | install-without-launch (C-01) |
| `nickname_screen_shown` | `FirstLaunchProfilePopup.cs:106` | quit at the legal notice (B-03) |
| `nickname_rejected` + reason code + `nonAscii` flag | `:133` | **the charset rule (B-01/B-02) — invisible today** |
| `registration_attempted` → existing counters | already built | technical failure past this point |

Payload: a client-generated installation UUID, the event name, a reason code, and the
app version. **No nickname text, no token.** With these, one day of traffic answers
questions 7 and 8 definitively.

The cheapest partial substitute needing **no** backend change: `nickname_rejected` is
already the only step with no log line at all — adding a single `Debug.Log` at `:133`
makes it visible to `adb logcat` (S-DEV-2) for supervised test installs.

---

## Constraints honoured

- No production behaviour changed; no nickname rule changed; nothing deployed.
- Both temporary probes (`QaMatrixProbeTest.java`, the .NET `NickQa` project) were
  deleted after recording results; `git status` shows only intended files.
- No test is reported as passed that was not executed — H5 and H6 are marked NOT RUN
  with manual steps rather than assumed.
- No guest token or nickname text appears in this document.
