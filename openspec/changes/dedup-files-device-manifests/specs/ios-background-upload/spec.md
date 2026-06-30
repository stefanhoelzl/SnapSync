## MODIFIED Requirements

### Requirement: Per-asset manifest generation and side-channel upload

The extension SHALL maintain a durable, device-global **accumulator** in the shared App-Group store
of per-asset manifest entries (per `device-manifest`: per asset its `assetId`, `creationDate`, and
per resource its `role`, `contentType`, `filename`, and `originalFilename`). It SHALL write or update
an asset's accumulator entry on **every discovery** of that asset — **even when the engine answers
`AlreadyUploaded`** — so the accumulator is a rebuildable cache reflecting every discovered-not-deleted
asset, not a source of truth. The accumulator MUST NOT be driven through the `SyncEngine`, the
`createJob` path, or the ledger.

On each cycle the extension SHALL **project** the accumulator to the current event's `device.json`
(filtering to assets whose capture date meets the event's cutoff; under the current whole-library
scope the projection is the identity) and **PUT it synchronously, in-cycle**, to
`<host>/event/<eventId>/device/<deviceId>` with `Content-Type: application/json` — **not** over a
background `URLSession`, and **not** via the engine or ledger. The extension SHALL be the **sole
writer** of `device.json`; each write is a complete, self-contained full-state snapshot (no
read-modify-write). It MAY skip the PUT when the projection is unchanged from the last write. A
process kill mid-PUT is benign — `device.json` is write-only in v1 and the next cycle re-projects and
re-PUTs, so the loss is caught and converges. The previous `PENDING`/`DONE` manifest markers and the
app's `handleEventsForBackgroundURLSession` manifest wiring are **removed**; the app reads no manifest
state.

#### Scenario: Accumulator entry is written on every discovery, including AlreadyUploaded

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** the extension still writes/updates `A`'s entry in the device-global accumulator (no job is
  created and the ledger is not written)

#### Scenario: Each cycle projects the accumulator and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the accumulator to the current event's `device.json` and PUTs it
  synchronously, in-cycle, to `<host>/event/<eventId>/device/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine/ledger involvement

#### Scenario: Unchanged projection skips the PUT

- **WHEN** a cycle's projection is byte-identical to the last written `device.json`
- **THEN** the extension MAY skip the PUT for that cycle

#### Scenario: A kill mid-PUT is caught next cycle

- **WHEN** the extension process is killed while the synchronous `device.json` PUT is in flight
- **THEN** the partial write is discarded and the next cycle re-projects the accumulator and re-PUTs
  `device.json`, converging without any cross-process marker

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built,
**event-independent** edge URL `<host>/files/device/<deviceId>/<filename>`, no signing), create a
system upload job via `creationRequestForJob(destination:resource:)`, and **then** report
`UploadStarted(job)` to the engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED`
is recorded only after the job exists, never before). The engine remains **event-blind** and keys by
the bare `filename`; ack-path recovery reads the destination URL's **last path segment**, which is the
unchanged `filename` under the new `/files/device/<deviceId>/` prefix. On `AlreadyUploaded` it SHALL
create no job and write nothing. Completion and failure outcomes are reduced into the ledger by the
drain (see "Completion and retry adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real device-partitioned edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally at `<host>/files/device/<deviceId>/<filename>`,
  a system upload job is created with it, and only after the create succeeds does the extension report
  `UploadStarted`, which records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED`)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the inputs it hands to `EdgeUploadRequestProvider` from three sources:
the runtime `EventConfigPayload` (`eventId`) read from the **shared Keychain** via the
`:capability:config` Keychain store; the stable per-install `deviceId` read from the **shared
Keychain** (per `device-identity`); and the compile-time edge **host** read from the extension
bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary). The `deviceId` SHALL be used to build
the event-independent byte URLs (`<host>/files/device/<deviceId>/<filename>`) and as the `device.json`
key. The extension SHALL re-read the Keychain payload **freshly at the start of every `process()`
cycle** — it MUST NOT cache a value read once at process construction. The extension process outlives
a single invocation, and an event (re)joined by the **app** process writes the Keychain but does not
notify the extension's in-memory config; a cached value would make a long-lived extension keep
uploading to a stale, previously-joined event even after the app shows the new one as joined. The
shared store therefore exposes a refresh (`reload()`) the extension calls before each read. When the
Keychain payload is **absent** (the extension woke before the user joined an event), the extension
SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never
crashing.

#### Scenario: Config present — provider built from host, eventId, and deviceId
- **WHEN** `process()` runs with an `EventConfigPayload` present in the shared Keychain
- **THEN** the extension builds `EdgeUploadRequestProvider` with `host` from `BackgroundUploadURLBase`,
  `eventId` from the payload, and `deviceId` from the shared Keychain, so byte URLs target
  `<host>/files/device/<deviceId>/<filename>` and `device.json` is keyed by that `deviceId`

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `EventConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

#### Scenario: A newly-joined event redirects uploads on the next cycle
- **WHEN** the extension process has already run a cycle for one event, the app then joins a different event (writing the new `eventId` to the shared Keychain), and the same extension process runs its next `process()` cycle
- **THEN** the extension re-reads the Keychain, builds `EdgeUploadRequestProvider` for the **newly-joined** `eventId` (the `deviceId` is stable across the switch), and uploads to the new event — it does not keep uploading to the event it read at process construction

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL prune ledger rows **and** accumulator entries for assets removed from the library,
via two paths driven from its discovery cycle (the ledger writes preserve the single-writer
invariant). This keeps the ledger honest about what still exists on device and, critically, removes a
row left non-`COMPLETED` by an asset deleted mid-upload — which would otherwise keep `pending > 0`
forever and hold the extension in the perpetual `processing` re-invocation loop (see "Cap-aware
creation and tri-state processing result"). On deletion the extension SHALL **also prune the asset's
device-global accumulator entry**, so the next projected `device.json` stops listing that asset (the
basis for a future deletion-correct restore). No remote object is deleted; the one-way model is
unchanged.

- **Incremental (every cycle):** when deriving the changed set from
  `fetchPersistentChanges(since:)`, the extension SHALL also collect each change record's
  `deletedLocalIdentifiers()` and, for each removed `localIdentifier` `L` (normalized `/`→`_` to
  match the stored `assetId`), call `deleteByAssetId(L)` so all of that asset's resource rows are
  removed, and remove `L`'s accumulator entry.
- **Reconcile (backstop):** on a full enumeration that completes with no `PHPhotosErrorLimitExceeded`,
  the extension SHALL call `retainAssets(liveAssetIds)`, where `liveAssetIds` is the set of
  `assetId`s of the resources it built during enumeration — pruning ledger rows and accumulator
  entries for assets no longer present, closing the gap for deletions that occurred while the
  persistent-change token was expired.

A re-added asset (e.g. recovered from "Recently Deleted") whose rows were pruned SHALL be treated
as new work: discovery finds no ledger entry, so the engine returns `Upload` and a fresh
(idempotent) job is created, and its accumulator entry is re-written. No `DELETED` state is
introduced and the upload decision is unchanged.

#### Scenario: Removed asset's rows and accumulator entry are pruned incrementally
- **WHEN** `fetchPersistentChanges(since:)` reports `deletedLocalIdentifiers` containing asset `L`,
  and the ledger holds rows for `L`'s resources
- **THEN** the extension calls `deleteByAssetId(L)` and removes `L`'s accumulator entry, so `L` no
  longer contributes to `pending`/`completed` and the next projected `device.json` omits it

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a non-`COMPLETED` ledger row, and a
  later cycle's change feed reports that asset as removed
- **THEN** the extension prunes the row (and its accumulator entry), the ledger reaches no pending rows, and `process()` can
  return `completed` instead of looping on `processing`

#### Scenario: Full enumeration reconciles against the live library
- **WHEN** a full enumeration completes with no `limitExceeded` and the ledger holds rows for an
  asset that is no longer present in the library
- **THEN** the extension calls `retainAssets(liveAssetIds)` and the absent asset's ledger rows and accumulator entry are removed

#### Scenario: Reconcile is skipped on a cap-truncated cycle
- **WHEN** a full enumeration stops early because job creation raised `limitExceeded`
- **THEN** the extension SHALL NOT call `retainAssets`, so the un-enumerated tail's rows are not
  wrongly pruned (reconcile runs only when enumeration completed fully — the same gate that
  advances the change token)

#### Scenario: Re-added asset re-uploads after pruning
- **WHEN** an asset whose rows were pruned reappears in the library (e.g. recovered from
  "Recently Deleted")
- **THEN** discovery finds no ledger entry for its resources, the engine returns `Upload`, a fresh
  job is created (the idempotent PUT targets the unchanged key), and its accumulator entry is re-written

### Requirement: Re-provision resets sync state

On a **valid `snapsync://` config (re)scan**, the host app SHALL re-provision the (possibly new)
event. The extension SHALL be re-registered (the disable→enable toggle). On its next cycle the
extension reconciles against the per-device file listing (`GET /files/device/<deviceId>`, see
`event-rejoin-reconciliation`): it **`resetTo`s** (atomic clear-and-seed) the ledger to one
already-uploaded row per stored file and **clears the discovery cursor** (forcing a full
re-enumeration). The device-global listing re-seeds the same files as already-uploaded, so **nothing
already stored re-uploads**, while the clear drops stale/phantom rows and the cursor clear
re-enumerates to find genuinely-unstored work. The device-global accumulator is **kept** and the
extension **re-projects** it to the **new** event's `device.json` path, then sets the joined-event
marker. The app decodes the deeplink only to gate this on a valid payload; the authoritative
decode/validate/persist still happens in the shared container intent.

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `snapsync://` config URL is opened for a different event
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing, clears the discovery cursor, keeps the accumulator, and
  re-projects `device.json` to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present under
  `/files/device/<deviceId>/…`
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid deeplink does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger, cursor, accumulator, and joined-event marker are untouched)
