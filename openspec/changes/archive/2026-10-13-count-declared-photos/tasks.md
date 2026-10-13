# Tasks

## 1. Backend: the declared total on the union read (D1)

- [x] 1.1 Add the optional `SnapSync-Declared` response header (integer ≥ 0) to the union read's `200` in
      `api/openapi.yaml`, described as the event-wide declared total. Verify that the `api-test` lint/redocly step
      passes and that `./gradlew :domain:wire:compileKotlinJvm` generates a nullable field on the union reply.
- [x] 1.2 In `api/src/db.ts`, add a `declaredTotal(db, eventId)` query: the assets the full union would serve
      (same completeness predicate, active and departed memberships) plus the incomplete assets of present
      memberships. Read it in `eventFiles` (`api/src/routes/shared.ts`) beside `unionPosition` and set the
      header on full and delta reads. Verify with a new `api/test/declared-total.test.ts` covering: a partly
      uploaded manifest counts every declared asset; a departed member's incomplete assets are dropped while
      their complete ones stay; a withdrawn declaration drops out; the value is equal on full and delta reads;
      an anonymous read gets it too.
- [x] 1.3 Document the header in the backend contract section of `docs/architecture.md` (the union read's
      answer). Verify that `deno task test` and `deno lint` (complexity plugin included) pass in `api/`.

## 2. Mock and contract

- [x] 2.1 Make `BackendMock`'s `BackendRouter` answer the header with the same rule (complete union plus
      present members' incomplete declarations). Verified by the Backend contract's mock binding (2.2) — the
      mock's backend rules are pinned there, beside the real `api/`, rather than in a second test of their own.
- [x] 2.2 Add a `BackendListingClauses` clause that pins the header against both the real `api/` (Live binding)
      and the mock: declare 3, land 1, then read and expect 3; leave, then expect the landed 1. Verify that
      `./gradlew :adapter:generic:app:jvmTest :adapter:generic:mock:jvmTest` passes, along with
      `ClauseCoversTest`/`ContractCoverageTest` in `:test:architecture`.

## 3. Device: store and read-model (D2–D4)

- [x] 3.1 Add a nullable `declared` column to downloads.db's `unionCursor` with migration `8.sqm`, write it in
      `upsertUnionCursor`, and add a `selectDeclared` query. Verify that `DownloadServiceTest` covers the write,
      the read and the clearing on `deleteUnionCursors`, and that `DatabasesContract`'s generated schema clauses
      (regenerate `generateSchemaStatements`) and the migration-from-oldest clause pass.
- [x] 3.2 Carry the header through `BackendEventUnionSource` into `UnionPage.declared: Long?`, and have
      `DownloadController` persist it with the cursor in the same transaction. Verify in `DownloadControllerTest`
      (`:test:feature`) that a read with the header stores it and a read without it stores null.
- [x] 3.3 Add `awaited` to `DownloadProgress` and compute it in `StoreDownloadStatusSource.refresh` as
      `max(0, declared − ownPublished − knownForeign)`. `ownPublished` is the asset count of `uploadedManifest`
      when its event matches. `knownForeign` counts every foreign row of the event, imported and unimportable
      included. The result is 0 when the membership does not receive or `declared` is null. Verify with unit
      tests covering: the sharer's 200 vs a received 6 gives `awaited` 193; a receive-only member's own count is
      0; with receiving switched off it is 0; an unimportable asset is not double-counted; it is clamped at 0;
      a backend without the header gives 0.

## 4. Presentation and UI (D5–D6)

- [x] 4.1 In `StatusContainerHost`, show the download arrow on `downloaded < total + awaited`, count received as
      `Progress(done, total + awaited)`, add `Syncing.waitingForOthers` (no pulse, no Wi-Fi hold, upload arrow
      hidden, `downloaded >= total`), and widen `sharingNote` to `waitingForOthers`. Verify in
      `JoinedLayerTest` with cases for 6 of 200 → `waitingForOthers`, own queued upload → not
      `waitingForOthers`, the ended event showing `WaitingFor(1)` beside it, and receive off → `Off`.
- [x] 4.2 Add `waitingForOthers` to `:ui:state`'s `SyncHealth.Syncing` (it is serializable, so check the
      control channel's `RigState` JSON) and word it in `AppStatusLine` in the D5 order. Change
      `status_sync_ongoing` to "Photos on their way…" / "Fotos sind unterwegs …" and add `status_waiting_photos`
      as "Waiting for photos…" / "Warte auf Fotos …". Verify that `AppStatusLineTest` covers each branch and
      that `HardCodedUiText` passes.
- [x] 4.3 Add a `StatusScreenTest` case for the ended-event note beside "Waiting for photos…". Verify that
      `./gradlew :ui:screens:jvmTest` passes.

## 5. Integration

- [x] 5.1 Add a `:test:integration` scenario over the JVM rig host with a second member played over HTTP: that
      member declares 200 and lands 7, and this device reads 6 or 7 of 200 received with "Waiting for photos…"
      once nothing is in flight. Then the member leaves with 193 undelivered, and the total drops to 7 and
      settles to "Up to date". Verify that `./gradlew :test:integration:test` passes.
- [x] 5.2 Add an upload-only scenario: everything received while this device uploads reads "Photos on their
      way…". Verify in the same suite.
- [x] 5.3 Run `./gradlew build` and `./gradlew architectureDiagrams` and commit any diagram change. Verify that
      the build is green and `git status` shows no stale `architecture/`.
- [ ] 5.4 Optionally confirm on the SE2 + A40 with the rig channel, using an event this workspace created: the
      A40 reads 6 of 200 while the SE2 reads 7 of 200. Verify against both devices' `/device` state.
