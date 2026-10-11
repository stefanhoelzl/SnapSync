# Tasks

## 1. The persisted recognition fact

- [x] 1.1 Add the per-membership `recognised` record to the download store schema (`DownloadStore.sq`) with a migration
  `7.sqm` that creates it empty (no backfill); verify with the storage suite over `DatabasesMock` and the
  `DatabasesContract` schema and migration clauses (`./gradlew build`).
- [x] 1.2 Expose it on `DownloadService` (write, read, dropped with the other event-scoped rows when another event is
  joined) and on the read-only `SuppressionService`; verify with the services suites, including the extension's
  read-only view of a store from an older schema (pauses, as today).
- [x] 1.3 No `PENDING_CLEANUPS.md` entry: the migration tolerates nothing older (it only adds an empty table); verify
  the design's Risks state why there is no backfill.

## 2. Recognition settles only on a read union

- [x] 2.1 `ReceivedPhotoAdoption`: an unreadable union answers "not settled" and writes no record; a pass that read
  the union writes `recognised(eventId)`; verify with `ReceivedPhotoAdoptionTest` (`:test:feature`): unreachable
  union → not settled, retried at the next `ensureAdopted`; success → recorded.
- [x] 2.2 Update the adoption's KDoc and the `importsReady` / `onGrantChanged` comments to the new rule ("nothing is
  received or shared before recognition"); verify no comment still describes the unreachable union as settling.

## 3. Every sharing path waits for recognition

- [x] 3.1 `CycleGateRead` withholds while the joined event is not recognised, in both processes; verify with the
  cycle-gate tests over the mocks (app and extension compositions): no job created, no manifest published.
- [x] 3.2 The status total and the own-photo progress follow the same gate, so the status stays on the neutral line
  while unrecognised and never counts an unrecognised received photo; verify with `OwnDeviceGalleryStatusSource` /
  `LedgerBackedSyncStatusSource` tests and a `:test:integration` scenario (rejoin with the backend held → neutral
  line, nothing uploaded; backend released → received photos not shared, status settles).
- [x] 3.3 The network returning, a foreground and a background wake each retry an unsettled pass; verify with a
  `:test:integration` scenario per trigger over the rig's JVM host.

## 4. Recognition on a selection change

- [x] 4.1 `onSelectionChanged` finds marked, unsuppressed photos in the snapshot (in memory) and, only if any, runs a
  recognition pass over a fresh union read before the walk and publish; an unreadable union marks the event not
  recognised; verify with a feature test (snapshot without marks → no union read; with marks → recognised before
  the publish; unreachable → withheld).
- [x] 4.2 `:test:integration` scenario: limited access, reinstall and rejoin, then received marked photos added to
  the selection → none uploaded, none in the manifest, status total unchanged; verify it passes on the JVM host.

## 5. Android: one candidate scope

- [x] 5.1 `DefaultGallery`: drop `CANDIDATE_SQL` and the `candidates` flag; every read is `DCIM/%`; update the
  adapter's KDoc and the decision D8 reference; verify `androidPlatformTest` (`ANDROID_EMU`): the gallery contracts
  pass, and a received photo in `DCIM/SnapSync/<event>/` is returned by `assets` and excluded only by the policy.
- [x] 5.2 `InMemoryGallery` (the mock) stops leaving folder-album photos out of `assets`; verify the mock-bound
  gallery contracts and the integration suite pass.
- [x] 5.3 `:test:integration` scenario: Android-shaped library (folder albums), reinstall, rejoin the same event →
  the album's received photos are not shared; an own photo moved into the album folder is shared; verify on the JVM
  host.

## 6. Integration and docs

- [ ] 6.1 Run `./gradlew build`, `./gradlew androidPlatformTest` and the `journeys (ios|android)` CI jobs on the
  branch; verify all green.
- [x] 6.2 `docs/architecture.md`: the recognition gate (where it is enforced, both processes) and Android's single
  scope; verify `./gradlew architectureDiagrams` leaves `architecture/` unchanged or is committed.
- [x] 6.3 `openspec validate close-share-back-gaps --strict` (pinned `npx --yes @fission-ai/openspec@1.13.2`); verify
  it passes.
