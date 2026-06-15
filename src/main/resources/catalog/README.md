# Catalog import directory (DEV)

Copy the Unity catalog files here, exactly as exported, with these names:

- `skins.json`
- `cases.json`

These files are NOT committed catalog content — they are dev import input. If
they are absent, the backend starts normally and the importer is a no-op (it
logs a warning and leaves the database untouched).

## How to enable the import

Set the property at startup (default is `false`):

    valocase.catalog.import-on-startup=true

The importer upserts skins / cases / case_entries by their Unity stable IDs
(IDs are preserved verbatim — never renamed, cleaned, or regenerated) and
neutralizes the old `sample_*` data. The whole import is transactional: if
parsing or validation fails, nothing is written.

## Expected JSON shape

Either a top-level array, or an object wrapping the array under `skins` /
`cases` (both are accepted). Unknown extra fields are ignored.

`skins.json`:

```json
{
  "skins": [
    {
      "skinId": "unity_stable_skin_id",
      "displayName": "Display Name",
      "weapon": "Vandal",
      "rarity": "PREMIUM",
      "vpValue": 1275,
      "resourceKey": "skins/whatever.png",
      "enabled": true
    }
  ]
}
```

`cases.json` (each `manualDropPool` element may be a bare skinId string, or an
object `{ "skinId": "...", "weight": 2 }`; weight defaults to 1):

```json
{
  "cases": [
    {
      "caseId": "unity_stable_case_id",
      "displayName": "Display Name",
      "price": 500,
      "resourceKey": "cases/whatever.png",
      "enabled": true,
      "manualDropPool": [
        "unity_stable_skin_id_a",
        { "skinId": "unity_stable_skin_id_b", "weight": 3 }
      ]
    }
  ]
}
```

## Field mapping

- Skin: `skinId`→id, `displayName`, `weapon`, `rarity`, `vpValue`,
  `resourceKey`→imageRef, `enabled`→active.
- Case: `caseId`→id, `displayName`, `price`→priceVp, `resourceKey`→imageRef,
  `enabled`→active, `manualDropPool`→case_entries.
