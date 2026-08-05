# ValoCase Backend — API Contract (Phase 1)

Base URL (local dev): `http://localhost:8080`
All endpoints are under `/api/v1`. Responses are JSON (`Content-Type: application/json`).

There is no JWT / OAuth / password login in Phase 1. The only credential is a
**guest token** returned at registration, sent on authenticated calls via the
`X-Guest-Token` header.

## Authentication flow

1. Call `POST /api/v1/guest` once. Store the returned `guestToken` on the device
   (persist it — it is the account identity).
2. On every authenticated call, send the header: `X-Guest-Token: <guestToken>`.

A missing, malformed, or unknown token returns `401`. A disabled account
returns `403`.

## Error format

All errors share one shape:

```json
{
  "timestamp": "2026-06-14T12:00:00Z",
  "status": 404,
  "error": "Not Found",
  "message": "Case not found: vandal_basic"
}
```

Common statuses: `401` (bad/missing token), `403` (account not active),
`404` (case not found / inactive), `422` (insufficient VP), `500` (server /
catalog misconfiguration).

---

## Endpoints

### GET /api/v1/health
No auth. Liveness check.

Response `200`:
```json
{ "status": "OK", "service": "valocase-backend" }
```

### POST /api/v1/guest
No auth. Creates a guest account + wallet with starting VP (17500).

Request body is **required**, and carries the nickname and country the player
already chose:

```json
{ "displayName": "Yiğit", "countryCode": "TR" }
```

Register only after the player has confirmed a nickname. The requirement is the
account-creation guard, not a formality: this endpoint is unauthenticated and
grants the starting balance, so a bare `{}` used to be enough to create an
account. Clients from 1.0.18 onward send the name; anything older cannot
register at all.

`displayName` rules — identical to the rename endpoint, so a name that is legal
here can never be illegal later:

- 3–20 characters, surrounding whitespace trimmed
- letters, digits and underscore only (`^[A-Za-z0-9_]+$`)

`countryCode` rules:

- ISO-3166-1 alpha-2 only, from the official 249-code set. `TR`, not `TUR`,
  `Turkey` or `Türkiye`.
- Accepted in any case and stored uppercase: `tr` is stored and returned as `TR`.
- Two uppercase letters is not the rule — `ZZ`, `XX`, `XK`, `UK` and `EU` are all
  rejected because they are not assigned codes.
- **The backend never sends a country name.** The label the player reads
  ("Türkiye - TR") is built client-side from the code, in the player's language.

`countryCode` is **optional during the migration window** and becomes required
when the backend property `valocase.registration.require-country-code` is turned
on. See "Country rollout" below.

Response `201`:
```json
{
  "accountId": "f1c2...uuid",
  "guestToken": "a9b8...uuid",
  "displayName": "Yiğit",
  "avatarId": "avatar_1",
  "countryCode": "TR",
  "status": "ACTIVE",
  "vpBalance": 17500,
  "diamondBalance": 0
}
```

`countryCode` is `null` for an account created without one.

`400` when `displayName` is missing or breaks the rules above, or when
`countryCode` is present but is not an official code — that one is refused
whether or not the country is required yet, because a client sending `Türkiye`
is broken rather than old. Nothing is written: no account, no wallet, no
starting balance.

### PATCH /api/v1/account/country
Auth required (`X-Guest-Token`). Changes the country from the Settings screen.

```json
{ "countryCode": "IN" }
```

Same validator and same normalisation as registration. The account changed is
the one the token resolves to; there is no `accountId` field and one would be
ignored. A rejected code leaves the stored country exactly as it was, including
the `null` an account created before the country screen carries — this endpoint
is how those players fill theirs in.

Response `200`:
```json
{
  "accountId": "f1c2...uuid",
  "displayName": "Yiğit",
  "countryCode": "IN"
}
```

`400` when the code is missing, blank, or not an official ISO code. A blank does
**not** clear the country: the picker cannot produce "no country", so a blank
arriving here is a client bug rather than an instruction.

### Country rollout

The country is **self-reported and unverified**. It is what the player picked
from a list — not derived from an IP address, not from the store locale, not
from the SIM, and nothing checks it. A player can deliberately select a country
they do not live in, and can change it at any time. Treat every country report
as a stated preference. Verifying it would need a separate mechanism that does
not exist today.

Three phases, in this order:

1. **Backend release (compatibility).** `countryCode` accepted and validated but
   optional. The client already in the store keeps registering; its accounts get
   `country_code = NULL`. Nothing is inferred to fill that gap.
2. **Unity release.** The client ships the country screen and starts sending
   `countryCode` on registration.
3. **Activation.** Once the old client is drained, set
   `REQUIRE_COUNTRY_CODE=true` (property
   `valocase.registration.require-country-code`). A registration without a
   country is then a `400`.

Doing 3 before 2 is what takes sign-ups offline, so the property ships as
`false` and is turned on deliberately.

### GET /api/v1/wallet
Auth required (`X-Guest-Token`). Current VP balance.

Response `200`:
```json
{
  "accountId": "f1c2...uuid",
  "vpBalance": 10000,
  "updatedAt": "2026-06-14T12:00:00Z"
}
```

### GET /api/v1/skins
No auth. All active skins.

Response `200` (array):
```json
[
  {
    "id": "skin_arcane_vandal_vandal",
    "displayName": "Arcane Vandal",
    "weapon": "Vandal",
    "rarity": "Exclusive",
    "vpValue": 1775,
    "imageRef": "Art/Skins/Vandal/...",
    "active": true
  }
]
```

### GET /api/v1/cases
No auth. All active cases (summary, no drop pool).

Response `200` (array):
```json
[
  {
    "id": "vandal_basic",
    "displayName": "Basic Vandal Case",
    "priceVp": 500,
    "imageRef": "Art/Cases/Basic_Vandal_Case",
    "active": true
  }
]
```

### GET /api/v1/cases/{caseId}
No auth. One case with its drop pool. `404` if the case id does not exist.

Response `200`:
```json
{
  "id": "vandal_basic",
  "displayName": "Basic Vandal Case",
  "priceVp": 500,
  "imageRef": "Art/Cases/Basic_Vandal_Case",
  "active": true,
  "drops": [
    {
      "skinId": "skin_arcane_vandal_vandal",
      "weight": 1,
      "displayName": "Arcane Vandal",
      "weapon": "Vandal",
      "rarity": "Exclusive",
      "vpValue": 1775,
      "imageRef": "Art/Skins/Vandal/..."
    }
  ]
}
```

### POST /api/v1/cases/{caseId}/open
Auth required (`X-Guest-Token`). Server-authoritative open: atomically debits
the case price, picks one weighted-random skin, and grants it to inventory.

Request: no body.

Response `200`:
```json
{
  "openingId": "0e1d...uuid",
  "caseId": "vandal_basic",
  "wonSkin": {
    "skinId": "skin_arcane_vandal_vandal",
    "displayName": "Arcane Vandal",
    "weapon": "Vandal",
    "rarity": "Exclusive",
    "vpValue": 1775,
    "imageRef": "Art/Skins/Vandal/..."
  },
  "newVpBalance": 9500,
  "inventoryItemId": "7a6b...uuid"
}
```

Errors: `401` (bad token), `404` (case missing/inactive), `422` (insufficient
VP — nothing is debited or granted), `500` (case has no valid drop entries).
The operation is all-or-nothing: a failure deducts no VP and grants no skin.

### GET /api/v1/inventory
Auth required (`X-Guest-Token`). All owned skin instances, newest first.
Inventory is per-instance: the same `skinId` can appear multiple times (each is
a separate `itemId`). There is no quantity field.

Response `200`:
```json
{
  "accountId": "f1c2...uuid",
  "count": 1,
  "items": [
    {
      "itemId": "7a6b...uuid",
      "skinId": "skin_arcane_vandal_vandal",
      "displayName": "Arcane Vandal",
      "weapon": "Vandal",
      "rarity": "Exclusive",
      "vpValue": 1775,
      "imageRef": "Art/Skins/Vandal/...",
      "source": "CASE_OPENING",
      "acquiredAt": "2026-06-14T12:00:00Z"
    }
  ]
}
```

---

## Notes for the Unity client

- IDs (`skinId`, `caseId`) are the Unity stable IDs, stored verbatim. Match them
  exactly to your local `skins.json` / `cases.json` (including non-ASCII
  characters).
- `imageRef` is the Unity `resourceKey` — use it to resolve the local art.
- Treat the backend as authoritative for VP balance and inventory. After
  `open`, prefer `newVpBalance` / `inventoryItemId` from the response (or re-pull
  `/wallet` and `/inventory`) rather than computing client-side.
- Send `X-Guest-Token` on `/wallet`, `/inventory`, and `/cases/{id}/open`.
  Catalog and health endpoints need no token.
