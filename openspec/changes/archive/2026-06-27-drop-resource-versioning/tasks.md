## 1. Engine seam (`:domain:engine`)

- [x] 1.1 `SyncEngine.kt`: remove `version` from `Resource` (and its KDoc paragraph). Remove
      `SyncDecision.ReUpload` and its `handle()` `logWork("ReUpload", …)` branch.
- [x] 1.2 `decide()`: drop `sameVersion`; `COMPLETED`/`REQUESTED` → `AlreadyUploaded`,
      `FAILED`/`null` → `Upload`. Update the class/`AlreadyUploaded` KDoc (no "at the same version").
- [x] 1.3 `complete()`, `started()`, `retry()`: drop the `version` argument passed to the `record*`
      calls. `mint()` is unchanged.

## 2. Ledger: schema, record ops, migration, dialect (`:domain:engine`)

- [x] 2.1 `Ledger.kt`: drop `version` from `LedgerEntry`; drop the `version` param from
      `recordRequested`/`recordCompleted`/`recordFailed` and the private `record` helper.
- [x] 2.2 `Ledger.sq`: remove the `version TEXT NOT NULL` column from `CREATE TABLE ledgerRow` and
      from every insert/upsert/select that names it; update `SqlDelightLedgerBackend.kt` row mapping.
- [x] 2.3 Add `dialect("app.cash.sqldelight:sqlite-3-35-dialect:2.3.2")` (via `libs.versions.toml`)
      to `domain/engine/build.gradle.kts` so `DROP COLUMN` parses.
- [x] 2.4 Add `2.sqm`: `ALTER TABLE ledgerRow DROP COLUMN version;` with a comment header documenting
      intent (v2→v3, row-preserving, immutable resources). Confirm `1.sqm` + `Ledger.sq` still parse
      under the 3.35 dialect and generated Kotlin types are unchanged.
- [x] 2.5 Update the in-memory + SQLDelight backend tests / contract:
      `InMemoryLedgerBackend.kt`, `LedgerBackendContract.kt`, `SqlDelightLedgerBackendTest.kt`,
      `LedgerWatcherTest.kt` — drop `version` from entry builders and assertions. Add a migration test
      that opens a pre-migration DB holding `COMPLETED` rows and asserts the rows survive without a
      `version` column.

## 3. Gallery derivation (`:domain:gallery`)

- [x] 3.1 `UploadKeys.kt`: delete `assetVersion()` and its KDoc; `UploadKeysTest.kt` drops its test.
- [x] 3.2 `PhotoLibraryResourceEnumerator.kt`: stop reading `asset.modificationDate`; drop the
      `version =` argument when constructing each `Resource`.
- [x] 3.3 `GalleryResourceEnumerator.kt`: update the KDoc that mentions `version`; update any
      in-memory fake enumerator that builds `Resource` with a version.

## 4. Re-join reconciliation (`:capability:rejoin`)

- [x] 4.1 `EventFilesSource.kt`: `RemoteFile` drops `lastModified` (→ `class RemoteFile(val filename:
      String)`); update its KDoc.
- [x] 4.2 `HttpEventFilesSource.kt`: `FileDto` drops `lastModified`; map to `RemoteFile(it.filename)`.
- [x] 4.3 `JoinEvent.kt`: drop `lastModifiedByName`/`parseInstant`; match locals against the listed
      filename set; seed `LedgerEntry(..., updatedAt = joinTime)` with no `version`.
- [x] 4.4 Tests: `HttpEventFilesSourceTest.kt` drops `lastModified` from the JSON + assertions;
      `JoinEventTest.kt` (the "falls back to join time when lastModified absent" test becomes
      "`updatedAt` is the join time") and `JoinThenEngineTest.kt` drop `lastModified`/`version`.

## 5. Backend list endpoint (`backend/`)

- [x] 5.1 `app.ts`: `FileEntry` drops `lastModified` (→ `{ filename, size, url }`); the
      `/event/:eventId/files` handler stops reading `e.LastChanged ?? e.DateLastModified`. Keep
      `size` + `url`.
- [x] 5.2 `app.test.ts`: drop `lastModified` from list expectations/fixtures; keep the `size`/`url`
      assertions. `deno task test` green.

## 6. iOS extension wiring (`:app:ios:photokit-extension`)

- [x] 6.1 `UploadCycle.kt`: `reconstruct()` drops the `version` field when rebuilding `Resource`.
- [x] 6.2 `UploadCycleTest.kt`: drop `version` from the `resource()` helper. (Discovery / update
      handling in `IosUploadJobPlatform.kt` is unchanged.)

## 7. Design source of truth (`docs/design.md`)

- [x] 7.1 §2.2: remove `version` from the `Resource` description and the `ReUpload` arm; rewrite the
      decision table to the state-only rules; drop `version` from `LedgerEntry`.
- [x] 7.2 §3.1: remove the `version =` (modificationDate) derivation note.
- [x] 7.3 §4: list response shape → `{ filename, size, url }`.
- [x] 7.4 §7/test list and any scattered `version`/`ReUpload` references: update to immutable-resource
      wording; note the row-preserving migration + the 3.35 dialect.

## 8. Verify

- [x] 8.1 `./gradlew build` green (all targets + JVM tests; engine compiles under the 3.35 dialect;
      ledger/gallery/rejoin/extension tests pass).
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green (iOS source sets compile without `version`).
- [x] 8.3 `deno task test` (backend) green with the 3-field list entry shape.
- [x] 8.4 Migration test confirms a pre-migration ledger's `COMPLETED` rows survive `2.sqm` (no
      re-upload on update).
