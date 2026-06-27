## 1. Ledger: atomic baseline reset (`:domain:engine`)

- [x] 1.1 Add `resetTo(entries: List<LedgerEntry>)` to the `LedgerBackend` seam (`sync-ledger`):
      atomic delete-all-then-insert-all, exactly one `changes` signal, entries stored verbatim (no
      clock stamping).
- [x] 1.2 Implement `resetTo` on the SQLDelight backend as a single transaction (delete all + insert
      each). Ensure interruption leaves the store unchanged and no premature ding. (Transaction
      rolls back on throw → store unchanged, ding skipped.)
- [x] 1.3 Extend the shared `LedgerBackendContract` with the reset scenarios (replace-all, one ding,
      reset-to-empty) so both the JVM-sqlite and native drivers run them; updated the in-memory and
      Darwin-delegate and status/photokit test fakes too. (Interrupted-leaves-unchanged is structural
      via the SQL transaction.)
- [x] 1.4 Expose `resetTo` to the app-side backend holder (alongside `clear()`); it remains a
      reset-family op on `LedgerBackend`, NOT on `LedgerWriter` (the app still constructs no
      `LedgerWriter`).

## 2. Gallery: shared resource enumeration (`:domain:gallery`)

- [x] 2.1 Define the enumeration seam (`GalleryResourceEnumerator`) in `commonMain` returning
      per-resource engine `Resource` `(filename, assetId, version, …)`; added `api(:domain:engine)`
      to gallery; moved the pure `uploadKey`/`resourceKind`/`fileExtension` + new `assetVersion`
      derivation into gallery `commonMain` (the single shared derivation).
- [x] 2.2 Added a settable in-memory implementation (`InMemoryGalleryResourceEnumerator`); the pure
      derivation is covered by the moved `UploadKeysTest` (+ `assetVersion` case); the fake is
      exercised by the JoinEvent tests (Group 4).
- [x] 2.3 Implemented the iOS PhotoKit-backed enumeration in gallery `iosMain`
      (`PhotoLibraryResourceEnumerator`) — moved from the extension.
- [x] 2.4 `IosUploadJobPlatform` now **delegates** both its full and incremental enumeration to the
      injected `GalleryResourceEnumerator`; `:app:ios:photokit-extension` gained the `:domain:gallery`
      dep; `compileIosMainKotlinMetadata` green.

## 3. Event file list seam (new `:capability`)

- [x] 3.1 Added Ktor (`ktor = 3.2.0`, client-core/darwin/mock) to the catalog; created
      `:capability:rejoin` (commonMain + iosMain) and `:capability:event-status`; registered both in
      `settings.gradle.kts`.
- [x] 3.2 Defined `EventFilesSource`/`RemoteFile` and `HttpEventFilesSource` (Ktor over an injected
      `HttpClient`, parses the flat array, maps errors to `Result`); the Darwin engine factory
      (`darwinHttpClient()`) lives in `iosMain`.
- [x] 3.3 `HttpEventFilesSourceTest` (MockEngine): targets `/event/<id>/files`, parses the array,
      `200 []`, non-2xx → failed `Result`, malformed body → failed `Result`.

## 4. Join use-case + status seam (new `:capability`)

- [x] 4.1 Defined `EventStatusSource`/`EventStatus` (`Idle/Joining/JoinFailed/Joined`) and the
      settable `MutableEventStatusSource` in `:capability:event-status`.
- [x] 4.2 Implemented `JoinEvent.runJoin`: `Joining` → `list` → enumerate → filename-matched subset →
      `COMPLETED` entries (local `version`, `updatedAt` = manifest `lastModified`, fallback join time)
      → `resetTo(seeds)` → clear cursor → `Joined`; list failure → `JoinFailed`. Producer-disable is
      the caller's job (task 6).
- [x] 4.3 Gate (`ensureJoined`): event configured ∧ ledger empty ∧ not settled; in-memory
      `joinedThisSession`/`failedThisSession` (set on success/failure; cleared by `onProvision`
      re-scan). Switch detection in `onProvision(previousEventId, newEventId)`.
- [x] 4.4 `JoinEventTest`: match/seed (matches only, local version, lastModified→updatedAt + fallback),
      no-op on non-empty ledger, fail→JoinFailed + no auto-retry, re-scan retries, switch resets +
      cursor clear, same-event leaves ledger, no-event no-op, empty-remote success stays settled.

## 5. Presentation + UI (`:domain:presentation`, `:domain:ui`, `:domain:ui:components`)

- [x] 5.1 Added `UiState.Joining` and `UiState.JoinFailed`.
- [x] 5.2 Folded `EventStatusSource` into the container (5-arg combine) + `reduceFrom` precedence
      (Setup > Joining/JoinFailed > hero; Joined/Idle fall through); added a defaulted
      `eventStatusSource` param (preserves all call sites). Reduction tests added (Joining/JoinFailed
      outrank, Joined falls through, Setup outranks Joining).
- [x] 5.3 Render `Joining` (Loading indicator + "Checking what's already backed up …") and
      `JoinFailed` (Error indicator + "Couldn't reach the server" / "Scan the event QR code again",
      no spinner). UI assertions added in `:domain:ui:jvmTest`. (Spec refined: JoinFailed uses the
      Error indicator rather than no-dot.)
- [x] 5.4 Added "Joining"/"Join failed" presets to the desktop harness (`PanelController` +
      `ControlPanel`); wired `eventStatusSource` into the harness container.

## 6. App wiring (`:app:ios`)

- [x] 6.1 `SnapSyncRoot` constructs `HttpEventFilesSource(darwinHttpClient(), host)` (host from the
      app Info.plist `BackgroundUploadURLBase`), `PhotoLibraryResourceEnumerator`, the `JoinEvent`,
      and a `MutableEventStatusSource`; injects the same `eventStatus` into the container.
- [x] 6.2 `reconcileThenEnable()` gates both enable sites (grant + provision): disable →
      `joinEvent.ensureJoined()` (seed + cursor clear) → enable only if satisfied; non-empty ledger
      enables directly; a failed join leaves the extension disabled.
- [x] 6.3 `onOpenUrl` success path captures the previous eventId, calls
      `joinEvent.onProvision(prev, new)` (switch resets the ledger + cursor) before `config.save`,
      then `reconcileThenEnable()`; invalid links still flash via the container.
- [x] 6.4 `./gradlew compileIosMainKotlinMetadata` green (the Linux proxy for the iOS closure); the
      Swift shell / xcodebuild simulator build is macOS-CI only. `:app:ios` stays wiring-only.

## 7. Backend (`backend/`)

- [x] 7.1 List handler now `decodeObjectName`s the stored name back to the uploaded filename; added a
      round-trip test (`IMG%20001.jpg` stored → listed as `IMG 001.jpg`). 30 backend tests pass.
- [x] 7.2 `deno test` (30 pass), `deno lint`, `deno fmt --check`, `deno check src/*.ts` all green.

## 8. Integration + checks (`:test:integration`)

- [x] 8.1 `JoinThenEngineTest` (in `:capability:rejoin`, crossing join→engine) seeds via the join's
      `resetTo`, then asserts the real `SyncEngine` yields `AlreadyUploaded` for a seeded resource and
      `Upload` for an un-seeded one (migration-shaped). NOTE: the standalone `:test:integration`
      module (full engine→status→presentation assembly) is a separately-planned module and was not
      scaffolded here; the core guarantee is covered by this test + the engine/presentation unit tests.
- [x] 8.2 `./gradlew build` green (all targets compile incl. iOS test compiles; JVM + offscreen UI
      tests pass).

## 9. Docs

- [x] 9.1 Updated `docs/design.md` (§3.2 status invariants, §3.2 platform, §4 list endpoint, §8
      scope): re-join reconciles + seeds; switch clears the ledger; same-event no-op; removed the
      "re-uploads from scratch" claim.
- [x] 9.2 Updated root `CLAUDE.md` `SNAPSYNC_DEEPLINK` + headless-loop notes: re-provision reconciles
      (no forced upload); use a fresh event id (or clear the zone) to observe real uploads.

## 10. Ship

- [ ] 10.1 Branch → PR → `/ship`.
- [ ] 10.2 Archive the change (`openspec archive add-rejoin-reconciliation`) after merge.
