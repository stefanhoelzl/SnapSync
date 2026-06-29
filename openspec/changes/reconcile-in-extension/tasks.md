## 1. Extension self-reconciliation

- [x] 1.1 Persist a `joinedEventId` marker in the App Group; gate reconciliation on `configured eventId != marker` (replacing the app-run, ledger-emptiness + in-memory-flag gate).
- [x] 1.2 In the extension, run the reconcile before creating jobs: fetch the completeness listing (the `EventFilesSource` now consumed by the extension), seed `COMPLETED` via `resetTo` for each complete asset's resources, clear the discovery cursor, and set the marker on success; on a fetch failure create no jobs that cycle and leave the marker unset.
- [x] 1.3 Event switch / reinstall / post-leave: on `configured eventId != marker` (or config absent), reset the extension's private ledger and clear the marker, then reconcile for the configured event.
- [x] 1.4 `commonTest`: marker mismatch triggers, match skips; a zero-row join sets the marker (no re-loop); a fetch failure defers and leaves the marker unset; a different event resets; a seeded resource yields `AlreadyUploaded`.

## 2. Remove the join-status UX

- [x] 2.1 Delete `EventStatus`/`EventStatusSource` and the `UiState.Joining`/`UiState.JoinFailed` states.
- [x] 2.2 Update the `sync-status-screen` reduction to consume permission + the `SyncStatus` snapshot only (no join-status precedence); remove the join scenarios.
- [x] 2.3 Drop `joining`/`join-failed` from the `event-invite-qr` joined-layer predicate.
- [x] 2.4 Update presentation tests: the reduction renders the listing snapshot during a (re)join, never a join state.

## 3. App composition + leave

- [x] 3.1 Composition root (`SnapSyncRoot`): wire the listing-backed `SyncStatusSource`; construct **no** ledger type and **no** `EventStatusSource`; enable the extension unconditionally on grant (no app-run join/seed, no disable-around-join).
- [x] 3.2 `LeaveEvent`: disable the producer, then clear config — only; drop the ledger `resetTo`, the cursor clear, and the `EventStatus → Idle` step (the extension resets on the next marker mismatch).
- [x] 3.3 `commonTest`: `LeaveEvent` disables then clears config and constructs no ledger type; a failed config clear leaves the user consistently joined.

## 4. Docs

- [x] 4.1 Update `docs/design.md`: reconciliation runs in the extension (`joinedEventId` marker); the join-status UX is removed; the app holds no ledger type.

## 5. Verification

- [x] 5.1 `./gradlew build` green (incl. new `commonTest`s; join-status tests removed).
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata` green.
- [ ] 5.3 On device: a reinstall self-seeds in the extension and re-uploads nothing already stored; a fresh event id uploads; an event switch resets and reconciles. *(Requires a physical iPhone + a CI dev IPA — not runnable in this environment; left for an on-device pass.)*
