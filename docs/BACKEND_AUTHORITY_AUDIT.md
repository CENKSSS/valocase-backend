# ValoCase Backend Authority Audit

Date: 2026-06-21

Scope: backend repository only. I did not inspect the Unity project. I also did not treat the Unity upgrade screen as current, per request. Backend upgrade code was still reviewed for authority boundaries because it is part of the backend economy surface.

Tests were not run. This was a source audit only.

## 1. Executive Summary

The backend is not consistently the single source of truth yet. Several core runtime flows are server-authoritative in the important way: case opening rolls rewards on the backend, wallet changes go through `WalletService`, inventory sell prices are calculated from catalog data, daily rewards are checked against server time, and public battle lobbies compute entry cost, rolls, winners, refunds, and rewards server-side.

The biggest problem is that some of the backend's "truth" is still imported from or shaped around Unity/client data. The catalog pipeline explicitly says Unity is the source of truth, and progression/battle/client UI rules are likely duplicated rather than exposed as backend read models. That is not a small documentation issue; catalog prices, skin values, drop pools, unlocks, and odds are economy rules.

There are also confirmed client-trust and temporary logic issues. The most serious is Earn VP: the backend computes the reward, but it trusts client-reported duration and tap timing. A modified client can claim a long, high-tap session immediately. Bot battles bypass category unlock enforcement that case opening and public lobby creation do enforce. Catalog detail can expose disabled-skin drops that case opening will not roll, so Unity can display a different drop pool than the backend actually uses.

Overall authority quality: mixed. The backend is stronger than a prototype in wallet/case/inventory/daily/lobby flows, but it still has critical and high-risk client-trust and duplicated-rule problems. It is not yet safe to add more economy features, ads, boosts, or richer PvP rewards without first establishing a backend-owned rules/config surface.

## 2. Confirmed Good Patterns

Wallet mutation is centralized.

Files:

- `src/main/java/com/cenk/valocase/wallet/service/WalletService.java`
- `src/main/java/com/cenk/valocase/wallet/domain/Wallet.java`
- `src/main/resources/db/migration/V4__account_wallet.sql`

Good pattern:

- `WalletService` owns credit/debit and records wallet transactions.
- `Wallet` has optimistic locking via `@Version`.
- Controllers do not expose a "set balance" endpoint.
- Most economy systems call `walletService.credit` or `walletService.debit` instead of accepting final balance from Unity.

Case opening is mostly backend-authoritative.

Files:

- `src/main/java/com/cenk/valocase/caseopening/service/CaseOpeningService.java`
- `src/main/java/com/cenk/valocase/caseopening/service/DropSelector.java`

Good pattern:

- Client sends only `caseId`.
- Backend loads the case, verifies active state, enforces category unlocks before charging or rolling, rolls the reward, debits wallet, grants inventory, emits mission progress, grants XP, and returns the resulting wallet/progression state.
- The operation is transactional.

Inventory selling calculates value on the backend.

Files:

- `src/main/java/com/cenk/valocase/inventory/service/InventoryService.java`
- `src/main/java/com/cenk/valocase/inventory/web/InventoryController.java`

Good pattern:

- Sell-one accepts `skinId`, not a sell price.
- Sell-all and sell-below-value compute totals from catalog `vpValue`.
- Wallet credit is still delegated to `WalletService`.

Daily rewards are server-time based.

Files:

- `src/main/java/com/cenk/valocase/daily/service/DailyRewardService.java`
- `src/main/java/com/cenk/valocase/daily/repository/DailyRewardStateRepository.java`

Good pattern:

- Claim availability is calculated from stored server timestamps.
- Claim uses a pessimistic lock when a state row exists.
- Unity cannot send reward amount or cooldown state.

Public battle lobbies have many correct server-owned rules.

Files:

- `src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java`
- `src/main/java/com/cenk/valocase/battle/web/BattleLobbyController.java`

Good pattern:

- Entry cost is computed from selected cases.
- Creator category lock is enforced during lobby creation.
- Join, leave, add-bot, timeout, refund, delayed start, result persistence, winner, and reward grant are backend-owned.
- The lobby response includes meaningful read state for Unity: status, entry cost, selected cases, slots, add-bot time, readiness, winner, rolls, totals, and reward status.

Upgrade execution and preview share backend valuation.

Files:

- `src/main/java/com/cenk/valocase/upgrade/service/UpgradeService.java`
- `src/main/java/com/cenk/valocase/upgrade/service/UpgradeChanceCalculator.java`

Good pattern:

- Execution consumes inventory item IDs owned by the account.
- Execution locks input items for the real upgrade path.
- Chance is computed on the backend and returned in the result.
- A preview endpoint exists and uses the same valuation/chance calculator for the single-target path.

Mission claims are backend-guarded.

Files:

- `src/main/java/com/cenk/valocase/mission/service/MissionService.java`
- `src/main/java/com/cenk/valocase/mission/event/MissionProgressListener.java`

Good pattern:

- Unity cannot directly mark missions complete.
- Mission progress is driven by backend gameplay events.
- Claim uses a locked row and only allows `COMPLETED -> CLAIMED`.

## 3. Risk Findings

### Finding 1: Earn VP trusts client-reported session time and tap timing

Area/system: Earn VP, wallet rewards

Files:

- `src/main/java/com/cenk/valocase/earnvp/service/EarnVpService.java`
- `src/main/java/com/cenk/valocase/earnvp/dto/EarnVpClaimRequest.java`
- `src/main/java/com/cenk/valocase/earnvp/web/EarnVpController.java`

What is wrong or risky:

`EarnVpService.claim` accepts `tapCount`, `sessionDurationMs`, `clientSessionId`, and `tapOffsetsMs` from the client. It caps the reward by the reported duration, but there is no server-owned session start, no server-owned elapsed time, no provider receipt, no nonce lifecycle, and no proof that the claimed 240-second session actually took 240 seconds. `MAX_DURATION_MS` is 240 seconds, while `MIN_CLAIM_INTERVAL_MS` is only 1 second.

Why it is amateur/temporary/client-trusting:

The backend computes the final VP amount, but it computes it from untrusted telemetry. This is still client-trusting logic. It prevents Unity from sending "give me 5000 VP" directly, but it still allows Unity or a modified client to fabricate the inputs that produce that reward.

Possible exploit or bad UX result:

A modified client can submit maximum duration, maximum taps, and favorable tap offsets repeatedly with new `clientSessionId` values. The unique `(account_id, client_session_id)` constraint only blocks replay of the same ID; it does not prove the session happened. This can mint VP.

Severity: Critical

Recommended fix:

Replace one-step claim with a server-owned Earn VP session lifecycle:

- `POST /api/earn-vp/session/start` creates a server session with start time, nonce, max duration, and policy.
- `POST /api/earn-vp/session/{id}/claim` validates server elapsed time, claim window, nonce, one-time status, and max accepted taps for actual elapsed time.
- Store session state server-side.
- Treat tap offsets as optional analytics, not authority.
- For ad-based Earn VP, require a backend-verifiable ad provider callback, signed receipt, or server-issued completion token.

Whether Unity changes are also needed:

Yes. Unity must start an Earn VP session before play and claim against that server session. It should display backend-provided limits and reward preview, not locally final rewards.

### Finding 2: Catalog pipeline explicitly makes Unity the source of truth

Area/system: Catalog, cases, skins, prices, drop pools, economy values

Files:

- `docs/CATALOG_PIPELINE.md`
- `tools/catalog/generate_catalog_migration.py`
- `src/main/resources/catalog/README.md`
- `src/main/java/com/cenk/valocase/catalog/importer/CatalogImportService.java`

What is wrong or risky:

`docs/CATALOG_PIPELINE.md` says "Unity's catalog is the single source of truth. The backend mirrors it." The generator repeats that Unity `skins.json` and `cases.json` are the source of truth. This directly violates the target architecture.

Why it is amateur/temporary/client-trusting:

Case price, skin value, active flags, and drop pools are not presentation data. They are backend economy rules. A pipeline that treats Unity config as authoritative creates a permanent duplication problem and invites Unity/backend drift.

Possible exploit or bad UX result:

If Unity catalog data is wrong, stale, or locally changed before generating a migration, the backend imports wrong economy rules. Even if players cannot upload catalog files at runtime, this is still the wrong ownership model and will cause displayed prices, odds, unlocks, and backend behavior to diverge.

Severity: High

Recommended fix:

Move catalog source of truth to backend-owned data:

- Backend-owned catalog files, admin tooling, or seeded database records should define prices, values, active state, drop weights, and unlock metadata.
- Unity should consume backend catalog/read endpoints and use `imageRef` only to map to local assets.
- The generator direction should be reversed: backend catalog exports a Unity display/config artifact, not Unity generating backend economy migrations.

Whether Unity changes are also needed:

Yes. Unity catalog files should become asset/display mappings only, or be generated from backend catalog data.

### Finding 3: Catalog numeric safety is not enforced by schema or generator

Area/system: Catalog, wallet spend, inventory value, battle entry cost, upgrade value

Files:

- `src/main/resources/db/migration/V2__catalog.sql`
- `tools/catalog/generate_catalog_migration.py`
- `src/main/java/com/cenk/valocase/catalog/importer/CatalogImportService.java`

What is wrong or risky:

`skins.vp_value`, `case_definitions.price_vp`, and `case_entries.weight` are `NOT NULL`, but there are no database `CHECK` constraints for non-negative prices/values or positive weights. The migration generator casts `vpValue` and `price` to integers but does not validate sane ranges. Invalid drop weights are silently coerced to `1` by the generator/importer instead of rejected.

Why it is amateur/temporary/client-trusting:

An economy backend must reject invalid rule data loudly. Silently defaulting bad weights and allowing negative prices/values makes catalog input too trusted.

Possible exploit or bad UX result:

A negative case price becomes a free open because case opening only debits when `priceVp > 0`. A negative or zero value can distort sell totals, upgrades, battle totals, leaderboards, and odds. Silent weight coercion means Unity/admins can believe one drop rate is configured while the backend uses another.

Severity: High

Recommended fix:

Add backend catalog validation and DB constraints:

- `case_definitions.price_vp >= 0`
- `skins.vp_value >= 0`
- `case_entries.weight > 0`
- mission rewards/targets, wallet deltas, battle entry cost, and charged VP should also have sane constraints.
- Generators/importers should reject invalid values instead of defaulting them.

Whether Unity changes are also needed:

Possibly. Unity data/config may need cleanup, but the backend must enforce the constraints regardless.

### Finding 4: Catalog detail can show a different drop pool than case opening uses

Area/system: Case opening preview/read data, odds display

Files:

- `src/main/java/com/cenk/valocase/catalog/service/CatalogService.java`
- `src/main/java/com/cenk/valocase/caseopening/service/CaseOpeningService.java`

What is wrong or risky:

`CatalogService.getCaseDetail` maps every case entry with an existing skin into `drops`; it does not filter out inactive skins. `CaseOpeningService.open` filters candidates to present and active skins before rolling.

Why it is amateur/temporary/client-trusting:

Unity may use `/cases/{caseId}` to display possible rewards or odds, but the backend roll uses a stricter eligible set. That means Unity can display rewards/weights that cannot actually be won.

Possible exploit or bad UX result:

Players see odds or drops that differ from the backend's real roll pool. This is a trust problem and a bad UX/regulatory smell for any loot-style system.

Severity: High

Recommended fix:

Make catalog read endpoints expose the exact roll-eligible pool:

- Filter inactive skins from public drop lists, or mark each drop with `eligibleForRoll`.
- Include `totalWeight`, normalized `oddsPercent`, and `rollPoolVersion`.
- Use the same backend helper for case detail, case-opening roll candidates, and battle roll candidates.

Whether Unity changes are also needed:

Yes. Unity should display backend-provided eligible drops/odds, not compute or assume them from local catalog.

### Finding 5: Standalone bot battle bypasses progression category locks

Area/system: Battle, progression, case unlocks

Files:

- `src/main/java/com/cenk/valocase/battle/service/BotBattleService.java`
- `src/main/java/com/cenk/valocase/caseopening/service/CaseOpeningService.java`
- `src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java`

What is wrong or risky:

Case opening enforces category unlocks before roll/charge. Public lobby creation also enforces category unlocks for the creator. `BotBattleService.createAndResolve` validates rounds, participant count, case existence, active state, and drop pool, but it does not enforce category unlocks.

Why it is amateur/temporary/client-trusting:

The same case can be blocked in one backend endpoint and allowed in another. That means unlock rules are duplicated/incomplete rather than centralized.

Possible exploit or bad UX result:

A low-level player can call `/api/v1/battles/bot` with a locked `caseId` and play for rewards before the backend would allow opening that case normally.

Severity: High

Recommended fix:

Centralize case access validation in a shared service:

- `requireCasePlayableByAccount(accountId, caseId, mode)` should check active state, drop eligibility, category lock, and any future mode-specific policy.
- Use it in case opening, standalone bot battle, public lobby creation, and any future battle endpoint.

Whether Unity changes are also needed:

Maybe. Unity should use backend-provided availability state, but the critical fix is backend enforcement.

### Finding 6: Public lobby joiners intentionally bypass level locks

Area/system: PvP battle lobbies, progression unlocks

Files:

- `src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java`

What is wrong or risky:

`BattleLobbyService.joinLobby` explicitly says joining is not level-locked and that only the creator's level controls which cases a lobby may use.

Why it is amateur/temporary/client-trusting:

If level locks are meant to gate access to case categories and rewards, applying them only to the host is not a backend-owned unlock rule; it is a social workaround. It lets an unlocked host grant lower-level accounts access to locked case outcomes.

Possible exploit or bad UX result:

Players can bypass progression by joining another account's lobby. With unlimited guest registration, a user can also create helper accounts and manipulate access/economy around lobby entry.

Severity: High if unlocks are intended per participant. Medium if product explicitly wants joiners to access host-selected cases.

Recommended fix:

Decide the rule explicitly and enforce it server-side:

- If unlocks are per-player, validate every real participant's level before join and before resolution.
- If host-gated lobbies are intentional, expose that as backend policy in lobby preview/read responses and document it clearly as product behavior.

Whether Unity changes are also needed:

Yes if the rule changes. Unity must display per-player eligibility and block/join UI based on backend response.

### Finding 7: Case category unlocks are inferred from string prefixes and unknown categories are open by default

Area/system: Progression, case unlocks, catalog

Files:

- `src/main/java/com/cenk/valocase/progression/domain/CaseCategory.java`
- `src/main/java/com/cenk/valocase/progression/service/ProgressionService.java`

What is wrong or risky:

`CaseCategory.fromCaseId` derives category from the prefix before `_`. If the prefix is unknown, it returns empty, and callers treat that as no lock.

Why it is amateur/temporary/client-trusting:

Unlock metadata belongs in backend-owned catalog data, not case ID naming conventions. A typo or new category silently becomes always open.

Possible exploit or bad UX result:

A future premium case with a new prefix can become open to level 1 players if the enum is not updated. Unity may show it locked locally while backend allows it, or vice versa.

Severity: Medium

Recommended fix:

Add explicit backend catalog fields such as `category`, `unlockLevel`, and `requiresLevel`. Unknown categories should fail closed until configured.

Whether Unity changes are also needed:

Yes. Unity should read `unlockLevel`, `lockedReason`, and `canOpen` from backend responses.

### Finding 8: Unlimited guest registration grants spendable VP with no abuse controls

Area/system: Account, wallet, economy abuse

Files:

- `src/main/java/com/cenk/valocase/account/service/AccountService.java`
- `src/main/java/com/cenk/valocase/account/web/AccountController.java`

What is wrong or risky:

`POST /api/v1/guest` creates a new account and grants `STARTING_VP = 10_000`. There is no rate limit, device limit, captcha, platform attestation, account binding, IP throttling, or abuse-state check in this backend.

Why it is amateur/temporary/client-trusting:

This trusts the client/device to create a reasonable number of accounts. For an economy game, account creation itself is a reward faucet.

Possible exploit or bad UX result:

An attacker can create unlimited wallets. Even without direct transfers, this pollutes leaderboards and can feed value through PvP/battle systems if rewards can be won from accounts funded by the starting grant.

Severity: High

Recommended fix:

Add abuse controls before expanding economy features:

- Rate-limit registration by IP/device/session.
- Consider device-bound guest identity or platform auth.
- Make starting VP non-transferable/training-only if PvP rewards can move value.
- Track wallet source and exclude starter-funded abuse from leaderboards/rewards if needed.

Whether Unity changes are also needed:

Likely. Unity may need account recovery/login, device identity, or platform auth integration.

### Finding 9: Random reward systems use `ThreadLocalRandom`, not security-grade or auditable randomness

Area/system: Case opening, battle rolls, upgrade rolls

Files:

- `src/main/java/com/cenk/valocase/caseopening/service/DropSelector.java`
- `src/main/java/com/cenk/valocase/upgrade/service/ThreadLocalUpgradeRng.java`
- `src/main/java/com/cenk/valocase/battle/service/BotBattleService.java`
- `src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java`

What is wrong or risky:

Drop selection and upgrade rolls use `ThreadLocalRandom`. This is server-side, so Unity is not directly choosing outcomes, which is good. But for a reward economy, it is neither cryptographic nor auditable/provably fair.

Why it is amateur/temporary/client-trusting:

Loot and upgrade outcomes are sensitive economy events. Non-auditable randomness is often acceptable for prototypes but weak for production trust.

Possible exploit or bad UX result:

Predictability risk is environment-dependent, but the bigger issue is player/operator trust: no stored roll seed, random value, total weight, or algorithm version is available to audit disputed outcomes.

Severity: Medium

Recommended fix:

Use a dedicated RNG service:

- Backed by `SecureRandom` or another production-grade source.
- Store roll metadata: algorithm version, total weight, random roll, selected entry, and catalog version.
- For high-trust needs, use commit-reveal/provably-fair seeds.

Whether Unity changes are also needed:

Not for enforcement. Unity may need to display audit/provably-fair metadata if that becomes a product feature.

### Finding 10: Mission progress updates are not locked and can lose concurrent progress

Area/system: Missions, progression, wallet reward eligibility

Files:

- `src/main/java/com/cenk/valocase/mission/service/MissionProgressService.java`
- `src/main/java/com/cenk/valocase/mission/repository/PlayerMissionRepository.java`
- `src/main/java/com/cenk/valocase/mission/domain/PlayerMission.java`

What is wrong or risky:

`MissionProgressService.recordProgress` loads `PlayerMission` with a normal finder, updates progress in memory, and saves. There is no lock or version on progress updates. Claiming is locked, but progressing is not.

Why it is amateur/temporary/client-trusting:

This is not client-trusting directly because events are backend-produced. It is temporary/weak backend state management for an economy reward path.

Possible exploit or bad UX result:

Concurrent actions can lose progress. First-time concurrent events can also race on the unique `(account_id, mission_id, period_key)` row creation. Depending on transaction timing, players may see missing mission progress or errors.

Severity: Medium

Recommended fix:

Use locked progress rows or atomic SQL upsert/increment:

- For existing rows, use `PESSIMISTIC_WRITE` or optimistic versioning.
- For first progress, use database upsert with conflict handling.
- Keep mission progress idempotent per gameplay event where possible.

Whether Unity changes are also needed:

No for enforcement. Unity should continue to display backend mission state.

### Finding 11: Inventory sell paths do not lock inventory rows, unlike upgrade execution

Area/system: Inventory, wallet, sell-all/sell-one

Files:

- `src/main/java/com/cenk/valocase/inventory/service/InventoryService.java`
- `src/main/java/com/cenk/valocase/inventory/repository/InventoryItemRepository.java`
- `src/main/java/com/cenk/valocase/upgrade/service/UpgradeService.java`

What is wrong or risky:

Upgrade execution uses `findForUpdateByIdInAndAccountId` to lock consumed inventory items. Sell-one loads the oldest item by account/skin without a lock. Sell-all and sell-below-value load account inventory without item locks.

Why it is amateur/temporary/client-trusting:

The backend computes sell value correctly, but the consumption path is weaker than upgrade. Shared inventory mutation paths should use the same ownership/locking standard.

Possible exploit or bad UX result:

Concurrent sell/sell/upgrade requests can race around the same item. The final transaction behavior may depend on Hibernate delete row counts and wallet optimistic locking, but the code does not deliberately serialize item consumption.

Severity: Medium

Recommended fix:

Add locked repository methods for sell paths:

- Sell-one should lock the selected item row.
- Sell-all/sell-below-value should lock the selected item rows before calculating and deleting.
- Consider a shared `InventoryConsumptionService` used by sell and upgrade.

Whether Unity changes are also needed:

No. This is backend integrity.

### Finding 12: Upgrade preview does not cover the multi-target execution path

Area/system: Upgrade preview/read endpoint

Files:

- `src/main/java/com/cenk/valocase/upgrade/service/UpgradeService.java`
- `src/main/java/com/cenk/valocase/upgrade/web/UpgradeController.java`
- `src/main/java/com/cenk/valocase/upgrade/dto/UpgradePreviewRequest.java`
- `src/main/java/com/cenk/valocase/upgrade/dto/UpgradeRequest.java`

What is wrong or risky:

Execution supports `targetSkinIds` with one or more targets. Preview accepts only a single `targetSkinId`. If multi-target upgrade is a real/current feature, Unity has no backend endpoint to preview the exact multi-target chance and target value that execution will use.

Why it is amateur/temporary/client-trusting:

Any real execute path must have a matching backend preview/read path. Otherwise Unity must guess or restrict itself locally.

Possible exploit or bad UX result:

Displayed chance/value can differ from the backend roll when multiple targets are selected. Players can see one chance and receive another.

Severity: Medium

Recommended fix:

Make preview request match execution:

- Accept `inputItemIds` and `targetSkinIds`.
- Return per-target values, total target value, input value, chance, `canUpgrade`, and reason codes.
- Keep a legacy single-target field only for backward compatibility.

Whether Unity changes are also needed:

Yes if Unity supports multi-target upgrades. If Unity does not, backend should consider disabling/removing multi-target execution until preview and UI are aligned.

### Finding 13: API contract documentation is stale and incomplete

Area/system: Unity integration, read/preview state, developer contract

Files:

- `docs/API_CONTRACT.md`
- Controllers under `src/main/java/com/cenk/valocase/**/web`

What is wrong or risky:

`docs/API_CONTRACT.md` still describes a Phase 1 API and says all endpoints are under `/api/v1`, but Earn VP uses `/api/earn-vp`. The document omits or under-documents current systems: battles/lobbies, missions, daily reward, progression embedded in wallet/open responses, leaderboards, account avatar/display-name, inventory sell endpoints, upgrade, and upgrade preview.

Why it is amateur/temporary/client-trusting:

Stale API docs push Unity developers to infer behavior, duplicate rules locally, or rely on old response shapes.

Possible exploit or bad UX result:

Unity can display incomplete progression/wallet/case/battle state, miss required backend fields, or implement local rules that drift from actual backend enforcement.

Severity: Medium

Recommended fix:

Update the contract or generate OpenAPI from controllers. Every economy endpoint should document:

- Request as user intent only.
- Backend-calculated fields.
- Error codes.
- Preview/read endpoint to call before action.
- Required Unity behavior after mutation.

Whether Unity changes are also needed:

Maybe. Unity integration should be checked against the updated contract.

### Finding 14: Public lobby code contains temporary debug logging

Area/system: Battle lobbies, production hygiene

Files:

- `src/main/java/com/cenk/valocase/battle/service/BattleLobbyService.java`

What is wrong or risky:

The service logs `LOBBY_DEBUG` messages including viewer account IDs, lobby IDs, statuses, and timestamps in list and scheduler paths.

Why it is amateur/temporary/client-trusting:

This is explicit debug residue in production code. It is not a direct economy exploit, but it is temporary "just make it work" logic.

Possible exploit or bad UX result:

Noisy logs, possible sensitive ID exposure in production log streams, and harder operational signal.

Severity: Low

Recommended fix:

Remove debug logs or downgrade them behind a proper debug logger/feature flag with redacted identifiers.

Whether Unity changes are also needed:

No.

### Finding 15: Avatar selection accepts arbitrary IDs

Area/system: Account cosmetics/profile

Files:

- `src/main/java/com/cenk/valocase/account/service/AccountService.java`
- `src/main/java/com/cenk/valocase/account/web/AccountController.java`

What is wrong or risky:

`updateAvatar` validates string shape and length, but not membership in a backend-owned avatar catalog or unlock state.

Why it is amateur/temporary/client-trusting:

If avatars are cosmetic choices with unlocks, rarity, purchases, or progression requirements, the backend currently trusts Unity to only send valid choices.

Possible exploit or bad UX result:

Players can set nonexistent or locked avatar IDs. Other clients may fail to render the avatar or display content the player has not earned.

Severity: Low now. Medium if avatars are monetized/unlocked cosmetics.

Recommended fix:

Add backend-owned avatar catalog and `canUseAvatar(accountId, avatarId)` validation before saving.

Whether Unity changes are also needed:

Yes if avatars have a backend catalog/unlock state. Unity should render available avatars from backend state.

## 4. Backend Preview/Read Endpoint Gaps

Earn VP session preview/status is missing.

Recommended endpoints:

- `POST /api/earn-vp/session/start`
- `GET /api/earn-vp/session/{sessionId}`
- `POST /api/earn-vp/session/{sessionId}/claim`

Suggested response shape:

```json
{
  "sessionId": "uuid",
  "serverStartedAt": "2026-06-21T12:00:00Z",
  "serverExpiresAt": "2026-06-21T12:04:00Z",
  "maxTapRatePerSecond": 10,
  "maxDurationMs": 240000,
  "baseReward": "1.6",
  "multiplierStart": "1.0",
  "multiplierStep": "0.02",
  "multiplierMax": "3.0",
  "claimable": false,
  "estimatedRewardVp": 0
}
```

Case availability/open preview is incomplete.

Current endpoints expose cases and drops, but not an authenticated per-player `canOpen` read model. `/cases/{caseId}` also does not guarantee the exact active roll pool unless fixed.

Recommended endpoint:

- `GET /api/v1/cases/{caseId}/open-preview`

Suggested response shape:

```json
{
  "caseId": "vandal_basic",
  "caseName": "Basic Vandal Case",
  "active": true,
  "priceVp": 500,
  "playerVpBalance": 1250,
  "canAfford": true,
  "category": "VANDAL",
  "unlockLevel": 9,
  "playerLevel": 9,
  "unlocked": true,
  "canOpen": true,
  "rollPoolVersion": "catalog-v52",
  "totalWeight": 20,
  "drops": [
    {
      "skinId": "skin_vandal_prime",
      "weight": 1,
      "oddsPercent": 5.0,
      "eligibleForRoll": true,
      "vpValue": 1775,
      "imageRef": "Art/Skins/Vandal/prime"
    }
  ]
}
```

Progression config/read endpoint is missing.

`/wallet` includes a progression snapshot, but there is no full backend-owned progression rules endpoint for Unity lock UI.

Recommended endpoint:

- `GET /api/v1/progression`

Suggested response shape:

```json
{
  "level": 3,
  "currentLevelXp": 10,
  "xpRequiredForNextLevel": 20,
  "totalXp": 50,
  "xpPerCaseOpen": 5,
  "categories": [
    {
      "category": "GHOST",
      "unlockLevel": 3,
      "unlocked": true
    }
  ]
}
```

Battle preview is missing for the standalone bot battle endpoint.

Recommended endpoint:

- `POST /api/v1/battles/bot/preview`

Suggested response shape:

```json
{
  "caseId": "ghost_basic",
  "rounds": 3,
  "participantCount": 4,
  "entryCost": 1500,
  "playerVpBalance": 5000,
  "canAfford": true,
  "unlocked": true,
  "canPlay": true,
  "dropsUseSamePoolAsCaseOpening": true,
  "rules": {
    "winnerRule": "HIGHEST_TOTAL_VP",
    "tieBreak": "LOWEST_SLOT_INDEX",
    "rewardRule": "WINNER_TAKES_ALL_ROLLED_SKINS"
  }
}
```

Public lobby preview could be stronger.

The current lobby response is fairly rich. A pre-create endpoint would still help Unity show exact cost/unlock issues before creating a lobby.

Recommended endpoint:

- `POST /api/v1/battles/lobbies/preview`

Suggested response shape:

```json
{
  "caseSelections": [
    {
      "caseId": "classic_basic",
      "quantity": 2,
      "priceVp": 500,
      "unlocked": true,
      "unlockLevel": 1
    }
  ],
  "maxSlots": 4,
  "rounds": 2,
  "entryCost": 1000,
  "playerVpBalance": 8000,
  "canCreate": true,
  "addBotDelaySeconds": 3,
  "startDelaySeconds": 1,
  "lobbyTimeoutSeconds": 120
}
```

Inventory sell preview is missing.

Current inventory items include `vpValue`, but the backend should provide an exact sell preview for bulk actions so Unity does not sum or filter locally as authority.

Recommended endpoints:

- `POST /api/v1/inventory/sell-preview`
- `POST /api/v1/inventory/sell-below-value/preview`

Suggested response shape:

```json
{
  "eligibleItemIds": ["uuid-1", "uuid-2"],
  "eligibleCount": 2,
  "totalVp": 3200,
  "newVpBalanceIfSold": 9000,
  "canSell": true
}
```

Upgrade preview should match execution.

Current preview only supports one target. Execution supports `targetSkinIds`.

Recommended endpoint adjustment:

- Keep `POST /api/v1/upgrade/preview`, but accept the same selection shape as execution.

Suggested response shape:

```json
{
  "canUpgrade": true,
  "chancePercent": 12.35,
  "reason": null,
  "inputValue": 1775,
  "targetValue": 3550,
  "inputItems": [
    {
      "itemId": "uuid",
      "skinId": "skin_vandal_prime",
      "vpValue": 1775
    }
  ],
  "targets": [
    {
      "skinId": "skin_vandal_elderflame",
      "vpValue": 3550
    }
  ],
  "rulesVersion": "upgrade-v1"
}
```

Ad rewards and ad boost endpoints are absent.

This is acceptable if ads are future work, but the architecture must be defined before implementation.

Recommended endpoints:

- `POST /api/v1/ads/reward/start`
- `POST /api/v1/ads/reward/verify`
- `GET /api/v1/ads/boost/status`

Suggested response shape:

```json
{
  "adSessionId": "uuid",
  "provider": "ad-network",
  "rewardType": "UPGRADE_PLUS_5",
  "boostPercent": 5,
  "serverExpiresAt": "2026-06-21T12:10:00Z",
  "verified": false
}
```

## 5. Duplicated Rule Risks

Catalog data is duplicated between Unity and backend.

Current risk:

- Unity JSON is documented as the source of truth.
- Backend DB/Flyway mirrors it.
- Unity likely also has local case/skin values for display.

Correct owner:

- Backend should own price, value, active state, drop pool, odds, category, unlock level, and availability.
- Unity should own local art assets and display presentation.

Safe removal path:

- Add backend catalog/config endpoints with all display-needed fields.
- Make Unity load catalog from backend at startup.
- Keep Unity resource keys only as asset references.
- Stop generating backend migrations from Unity config.

Progression unlocks are likely duplicated.

Current risk:

- Backend has hard-coded `CaseCategory` unlock levels.
- Unity lock UI probably needs the same unlock levels.
- There is no dedicated progression rules endpoint.

Correct owner:

- Backend should own unlock levels and case availability.

Safe removal path:

- Add explicit category/unlock metadata to backend catalog.
- Expose authenticated `canOpen`/`canPlay` per case.
- Unity only mirrors backend-provided lock state.

Battle rules are likely duplicated in Unity UI.

Current risk:

- Constants exist in backend: rounds 1..5, participants 2..4, add-bot delay 3 seconds, start delay 1 second, lobby timeout 2 minutes, connection timeout 15 seconds, tie-break lowest index, winner-takes-all.
- Unity needs these to render UI and timers.

Correct owner:

- Backend should own all battle constraints and timing rules.

Safe removal path:

- Expose battle config/read endpoints.
- Return server timestamps and allowed actions in lobby responses.
- Unity should not locally decide if add-bot/start/join is allowed except as a UI hint.

Earn VP rules are duplicated or client-driven.

Current risk:

- Backend owns constants, but trusts client duration/timing.
- Unity likely also computes local reward feedback.

Correct owner:

- Backend should own session timing, max reward, anti-spam policy, and final reward.
- Unity may show an estimate from backend session status.

Safe removal path:

- Add server sessions and status responses.
- Unity displays backend estimates and sends only tap events/claim intent.

Upgrade rules are partly duplicated.

Current risk:

- Backend owns execution and single-target preview.
- Multi-target execution has no matching preview.
- Unity upgrade screen is known not current, so actual Unity duplication is uncertain.

Correct owner:

- Backend should own target eligibility, input eligibility, value calculation, chance, boost state, and roll.

Safe removal path:

- Make preview match execution.
- Add a rules version in preview and result.
- Unity displays preview response exactly.

Daily/mission rules are likely duplicated in UI copy.

Current risk:

- Rewards and cooldowns are backend-owned.
- Unity may still hard-code reward labels, cooldown text, or mission targets if it does not rely on `/daily` and `/missions`.

Correct owner:

- Backend owns claimability, progress, rewards, cooldowns, and reset times.

Safe removal path:

- Unity should render only backend response values.
- Keep mission definitions fully backend-provided.

## 6. Recommended Architecture Standard

Backend should own:

- Wallet balance and every wallet mutation.
- Catalog economy values: case prices, skin values, drop weights, active flags, category, unlock levels, odds, and roll pool version.
- Case opening roll, charge, reward grant, XP grant, and mission progress.
- Inventory ownership, sell eligibility, sell value, deletion/consumption, and duplicate handling.
- Upgrade input eligibility, target eligibility, valuation, chance, boosts, roll, consumption, reward grant, and preview.
- Battle lobby state, entry cost, selected cases, participant eligibility, bot rules, start timing, refunds, result, winner, reward distribution, and result read model.
- Progression: XP gains, level, unlocks, and enforcement on every endpoint that touches locked content.
- Daily/missions: status, progress, cooldown, rewards, claim transitions, and anti-spam.
- Earn VP/ad rewards: server sessions, provider verification, cooldowns, boost state, and final grants.
- Randomness and audit records for every reward roll.
- Abuse controls: guest creation limits, claim rate limits, suspicious wallet/reward patterns.

Unity should own:

- Rendering.
- Input collection.
- Local animations.
- Local asset lookup by backend-provided IDs/resource keys.
- Sending user intent, such as "open this case", "join this slot", "sell these items", "claim this mission", or "attempt this upgrade".
- Temporary UI hints based on backend read responses.

Unity should not own:

- Final rewards.
- Final prices or sell values.
- Final success chance.
- Unlock enforcement.
- Wallet balance changes.
- Mission completion.
- Claim availability.
- Battle winner/result/refund/reward.
- Ad completion or boost validity.

Future feature standard:

Every economy action should have two backend surfaces:

- A read/preview endpoint that returns the exact backend-calculated state Unity needs to display.
- A mutation endpoint that accepts user intent only and recomputes/validates everything server-side.

Every mutation response should include enough fresh state for Unity to render correctly without guessing:

- New wallet balance when VP changes.
- Granted/consumed inventory item IDs when inventory changes.
- Progression delta when XP/level changes.
- Mission/daily status when claimability changes.
- Roll/audit IDs for reward events.
- Reason/error codes for blocked actions.

Every important rule should have one backend implementation path:

- Do not duplicate active case validation across services.
- Do not duplicate roll candidate filtering across catalog/case/battle.
- Do not duplicate inventory consumption across sell/upgrade.
- Do not duplicate progression unlock checks across endpoints.

## 7. Prioritized Action Plan

First fixes before adding more features:

1. Fix Earn VP authority. Add server-owned sessions and stop trusting client-reported duration as final authority.
2. Fix bot battle progression enforcement. Standalone bot battles must use the same case access validation as case opening and lobby creation.
3. Fix catalog detail/drop pool mismatch. Public drop lists must match the roll-eligible backend pool and include normalized odds.
4. Decide and enforce public lobby joiner unlock policy. If locks are per-player, validate every joiner.
5. Stop treating Unity catalog as the source of truth. Move economy catalog ownership to backend-owned data and have Unity consume backend read models.

Quick wins:

- Remove or gate `LOBBY_DEBUG` production logs.
- Update `docs/API_CONTRACT.md` or generate OpenAPI.
- Add backend preview for standalone bot battle entry cost/unlock/can-play state.
- Add inventory sell preview endpoints for bulk sell actions.
- Add upgrade multi-target preview parity or disable multi-target execution until preview is aligned.
- Add explicit `unlockLevel`/`category`/`canOpen` to case responses.

Larger refactors:

- Create a shared `CaseRulesService` for active case, category unlock, roll candidate, total weight, odds, and availability checks.
- Create a shared `InventoryConsumptionService` for sell and upgrade item locking/deletion.
- Move case category/unlock metadata into backend catalog schema instead of string-prefix inference.
- Add DB `CHECK` constraints and importer/generator validation for non-negative prices/values, positive weights, positive mission targets/rewards, and sane charged/entry values.
- Replace `ThreadLocalRandom` reward rolls with a dedicated auditable RNG service.
- Add guest/account abuse controls before enabling richer PvP, trading, ad rewards, or transferable rewards.

Uncertainties to verify next:

- Unity project was not inspected, so any statement that Unity "likely" duplicates rules should be verified in the Unity codebase.
- The current upgrade screen was intentionally excluded from Unity-side conclusions.
- It is unclear whether public lobby joiners are intentionally allowed to access host-unlocked cases as final product design. The backend comment says yes, but the core principle says backend should enforce unlocks. Product decision needed.
- It is unclear whether avatar IDs are meant to be free-form or catalog/unlock-controlled. If they are purely local/free cosmetics, the current risk is low.
- It is unclear whether multi-target upgrade is a current player-facing feature. If not, the backend should still avoid exposing an execution path that has no matching preview.
