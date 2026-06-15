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
No auth. Creates a guest account + wallet with starting VP (10000).

Request: no body.

Response `201`:
```json
{
  "accountId": "f1c2...uuid",
  "guestToken": "a9b8...uuid",
  "displayName": null,
  "status": "ACTIVE",
  "vpBalance": 10000
}
```

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
