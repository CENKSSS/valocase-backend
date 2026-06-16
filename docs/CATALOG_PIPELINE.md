# Catalog pipeline

Unity's catalog is the single source of truth. The backend mirrors it through
generated Flyway migrations, so adding or changing a case is one edit plus one
command.

Source of truth (read-only for the backend):

- `C:\Users\cenk_\ValoCase\Assets\_ValoCase\Resources\Config\skins.json`
- `C:\Users\cenk_\ValoCase\Assets\_ValoCase\Resources\Config\cases.json`
- Case art: `C:\Users\cenk_\ValoCase\Assets\_ValoCase\Resources\Art\Cases`

The generator: `tools/catalog/generate_catalog_migration.py` (Python 3, no
dependencies). It validates the catalog and writes the next migration:
`src/main/resources/db/migration/V{next}__catalog_sync.sql`.

## Adding a new case

1. Add the case PNG to `Art\Cases`, named to match the case `resourceKey`
   (a `resourceKey` of `Art/Cases/My_Case` expects `My_Case.png`).
2. Define the skins and the case in the Unity catalog: add any new skins to
   `skins.json`, and add the case to `cases.json` with `caseId`, `displayName`,
   `price`, `resourceKey`, `enabled`, and a `manualDropPool` of existing skinIds.
3. Validate first (writes nothing):

   ```
   python tools/catalog/generate_catalog_migration.py --dry-run
   ```

4. Generate the migration:

   ```
   python tools/catalog/generate_catalog_migration.py
   ```

5. Run the backend tests (applies the migration against your local DB and
   confirms nothing regressed):

   ```
   ./mvnw test
   ```

6. Commit and push the new `V{next}__catalog_sync.sql`.
7. Wait for Railway to deploy `main`; Flyway applies the migration on boot.
8. Test in Unity: open the case against the production backend.

## What the migration does

- Upserts every skin from `skins.json` (`ON CONFLICT (id) DO UPDATE`).
- Upserts every case from `cases.json`.
- Rebuilds `case_entries` only for the cases present in `cases.json`
  (delete-then-insert; nothing references `case_entries`).
- Keeps all IDs stable and never touches players, inventory, wallets, or
  transaction/history tables.
- Never edits existing migrations.

Skins are upserted, not deactivated: removing a skin from `skins.json` leaves
its row as-is so existing inventory still resolves. To retire a skin, set its
`enabled` to `false` in `skins.json` and re-run the generator.

## What the generator validates

- `skins.json` and `cases.json` parse.
- No duplicate `skinId`; no duplicate `caseId`.
- Every `manualDropPool` skinId exists in `skins.json`.
- No enabled case references a disabled skin.
- Every enabled case has `caseId`, `displayName`, `price`, `resourceKey`,
  `enabled`, and a non-empty `manualDropPool`.
- Case PNG exists in `Art\Cases` for each `resourceKey` (warning only; a missing
  or misnamed PNG does not block generation).

Any hard validation error stops generation with a non-zero exit code and writes
no file. Warnings are printed but do not block.

Default paths point at the local Unity project; override with `--skins-json`,
`--cases-json`, and `--art-cases-dir` if needed.
