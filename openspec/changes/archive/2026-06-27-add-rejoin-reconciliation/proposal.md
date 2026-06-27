## Why

When a device re-joins an event against an **empty ledger** — after a delete+reinstall (the App
Group is wiped, `deviceId` re-mints) **or** a destructive ledger-schema migration on an app update —
the extension re-enumerates the whole library and **re-uploads every photo**, even though the objects
already sit in the event's storage under the old `deviceId`. The backend already exposes a read of
those objects (`bunny-list-endpoint`, `GET /event/<id>/files`); nothing on-device consumes it yet.

This change makes a re-joining device **reconcile against storage before its extension uploads**:
pull the per-event file list, match it against the local library by the reinstall-stable
`filename`, and pre-seed the ledger so already-stored photos are recorded `COMPLETED` and skipped.
It must also make the **status immediately correct on join** (the seeded counts show before the
OS-scheduled extension ever runs).

## What Changes

- **New on-join reconciliation gate.** Before the background-upload extension is enabled (at every
  enable site — permission-grant and re-provision), if an event is configured **and the ledger has no
  rows** **and** a join has not already been settled this process, the app performs a **join**:
  `EventStatus = Joining` → `GET /event/<id>/files` → enumerate the local library → seed `COMPLETED`
  rows for filename matches → clear the discovery cursor → `EventStatus = Joined` → enable the
  extension. The status screen reflects the seeded counts the instant `Joined` is reached — **no
  extension run required**.
- **Block until success; no auto-retry.** A failed list fetch reduces to `UiState.JoinFailed`
  ("scan the QR again"); the join is **settled for the session** (success *or* failure) and the only
  retry is a manual QR re-scan (or a fresh launch, which makes one attempt). The extension is not
  enabled until a join succeeds.
- **Event switch vs re-join.** A scanned/deeplinked eventId that **differs** from the current one
  (compared via the Keychain config) resets the ledger and reconciles for the new event; the **same**
  eventId against a non-empty ledger is a **no-op**. No persistent "joined" marker — the ledger's own
  emptiness is the wipe signal, so reinstall and migration both self-heal without a marker that can
  desync.
- **Seeding is an app-side *reset*, not a record.** The app gains an atomic `LedgerBackend.resetTo`
  (delete-all + insert-all, single change-signal) and seeds with the extension **disabled** — so the
  extension remains the sole *record* writer and the app still never constructs a `LedgerWriter`.
- **Shared library enumeration.** The PhotoKit resource → `(filename/uploadKey, assetId, version)`
  derivation moves into `:domain:gallery` as the single implementation; the extension delegates to
  it, so an app-seeded `version` is byte-identical to what the extension later recomputes (matches →
  `AlreadyUploaded`).
- **Network client in the app.** A new `EventFilesSource` (Ktor multiplatform) fetches the list; the
  app makes no HTTP today, so Ktor is introduced.
- **Matching is filename-only** on the encoding-safe `uploadKey`; a photo edited between its original
  upload and the re-join is accepted as **not** re-uploaded (seeded at the local version).
- **BREAKING (dev-only behavior):** re-provision **no longer forces a fresh whole-library upload** —
  it now reconciles. The `SNAPSYNC_DEEPLINK` dev trigger loses its "drive a fresh per-build upload"
  guarantee; the dev verification loop will be handled separately.

## Capabilities

### New Capabilities
- `event-rejoin-reconciliation`: the on-join reconciliation feature — the `EventFilesSource` list
  fetch seam, the `JoinEvent` use-case (fetch → enumerate → match → `resetTo(seeds)` → clear cursor),
  the `EventStatusSource` seam and `EventStatus` (`Idle`/`Joining`/`JoinFailed`/`Joined`), the
  enable-time gate (ledger-emptiness + session-settled + Keychain-eventId switch detection), and the
  `UiState.Joining` / `UiState.JoinFailed` semantics (block until success, re-scan to retry).

### Modified Capabilities
- `sync-ledger`: add the app-side atomic reset `LedgerBackend.resetTo(entries)` to the reset family
  (alongside `clear()`); state the writer split explicitly — the extension is the single *record*
  writer; the app owns whole-ledger *resets* (`clear`, `resetTo`), run only with the extension
  disabled, and still never constructs a `LedgerWriter`.
- `gallery-status`: add a resource-enumeration seam (`(filename, assetId, version)` per resource)
  beside the existing count, as the single shared derivation the extension delegates to.
- `ios-app-shell`: the extension-enable path gains the reconcile gate as a precondition and clears
  the discovery cursor on reconcile; the developer launch-environment trigger drops its "forces a
  fresh whole-library upload" promise (re-provision reconciles).
- `setup-gate`: the reduction precedence gains a **join phase** — once config + permission are
  satisfied, `Joining`/`JoinFailed` precede the sync hero states.
- `sync-status-screen`: the snapshot→`UiState` reduction combines `EventStatus`, and the screen
  renders `UiState.Joining` (preparing) and `UiState.JoinFailed` (re-scan prompt).
- `bunny-list-endpoint`: add the requirement that a listed `filename` round-trips byte-for-byte with
  the uploaded filename (the consumer matches on it), backed by a round-trip test for a
  percent-encoded name; record the non-pagination completeness assumption of bunny Storage LIST.

## Impact

- **New module(s)**: a capability module for `EventFilesSource` (Ktor) + `JoinEvent` +
  `EventStatusSource` (`commonMain` logic, tested; `iosMain` Ktor Darwin engine). New dependency:
  **Ktor client** (`gradle/libs.versions.toml`).
- **`:domain:gallery`**: new enumeration seam (`commonMain` port + `iosMain` PhotoKit impl + settable
  fake); gains a `:domain:engine` edge (or a shared resource type) for the `Resource` shape.
- **`:domain:engine`**: `LedgerBackend.resetTo` on the seam + SQLDelight impl (one transaction);
  contract tests.
- **`app/ios/photokit-extension`**: `IosUploadJobPlatform` full-enumeration path delegates to the
  shared `:domain:gallery` derivation (no behavior change); gains a `:domain:gallery` dep.
- **`:app:ios`**: `SnapSyncRoot` wires `EventFilesSource`/`JoinEvent`/`EventStatusSource`, runs the
  gate (disable → `resetTo` → clear cursor → enable), and the switch-vs-rejoin decision in
  `onOpenUrl`; injects `EventStatusSource` into the container.
- **`:domain:presentation` / `:domain:ui`**: `UiState.Joining` / `UiState.JoinFailed` + reduction +
  rendering.
- **`:test:integration`**: seeded-row → `AlreadyUploaded` end-to-end test.
- **`backend`**: round-trip encoded-filename test (no route change).
- **Docs**: `docs/design.md` (re-join reconciliation; drop "re-provision forces fresh upload"); root
  `CLAUDE.md` "On-device iOS" loop note.
- **Out of scope**: network-reachability auto-retry; the replacement dev upload-verification loop;
  size-based match hardening; list pagination (bunny Storage LIST is non-paginated).
