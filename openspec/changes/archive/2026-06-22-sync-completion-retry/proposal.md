## Why

`real-s3-upload` shipped real presigned uploads but deliberately kept **drain-all**: the extension
acknowledges every system job without inspecting its outcome, never reports completion or failure,
and the ledger stops at `REQUESTED`. Two consequences follow. First, **the status screen can never
reach "backed up"** — `LedgerSyncStatusSource` projects `COMPLETED` aggregates, and nothing ever
records `COMPLETED`, so success is only observable out-of-band (the object in the bucket). Second,
**there is no retry**: a failed upload is acknowledged and forgotten; the photo is re-uploaded only
if its asset later changes or the change token expires. For a one-way photo backup, "silently never
retried" is the wrong failure mode.

This change makes the extension's drain a real **completion + retry engine**, persists the discovery
cursor so short-lived extension wakes stop re-enumerating the whole library, and handles the system's
in-flight job cap so a large backlog drains across cycles without losing or duplicating work.

Doing it correctly forces a **core engine model change**. Today the engine treats `REQUESTED` as
"a hope" and re-uploads on it — a deliberate crash-safety choice (`design.md §2.2`) that means
re-deriving the change feed would create **duplicate** jobs for work already in flight, which in turn
would need a separate persisted "residue" list to avoid thrashing the job cap. We instead make the
**ledger authoritative for in-flight state**: `REQUESTED` (same version) means "a job exists — skip
it." That single flip makes re-derivation idempotent (the cap-resume path needs no residue store),
at the cost of moving the `REQUESTED` write to **after** the job is actually created (write-after-act)
so that `REQUESTED` always implies a real job. The crash window then degrades to a rare, bounded,
self-correcting duplicate instead of a stuck photo.

## What Changes

- **Engine: the ledger becomes authoritative for in-flight work (BREAKING decision semantics).**
  `decide(ResourceChanged)` becomes a **pure query** (it reads the ledger and mints a request but
  writes nothing): `COMPLETED`/`REQUESTED` with the same version → `AlreadyUploaded` (skip);
  `FAILED`, absent, or changed-version → `Work`. This reverses the prior rule that a `REQUESTED`
  "hope" always re-uploads.
- **Engine: write-after-act via a new `UploadStarted` observation event.** Platforms report
  `SyncEvent.UploadStarted(job)` **after** the upload job is created; only then does the engine
  record `REQUESTED`. `decide()` and `UploadFailed` no longer write `REQUESTED`. The three lifecycle
  events (`UploadStarted`→`REQUESTED`, `UploadFailed`→`FAILED`, `UploadCompleted`→`COMPLETED`) become
  the **only** ledger writers, each an unconditional idempotent upsert.
- **Extension: real completion + retry adjudication replaces drain-all.** The drain inspects each
  system job: `.retry` set (first failure) → `UploadFailed` → `retryWithDestination` with a freshly
  presigned URL → `UploadStarted`; `.acknowledge` set → `Succeeded` records `COMPLETED` then
  `acknowledge`; an exhausted `Failed`/`Cancelled` job records `FAILED`, re-creates a fresh job from
  the job's own `PHAssetResource`, and is **acknowledged only on a successful re-create** (on the cap
  it is left un-acknowledged so the system re-presents it next cycle).
- **Retention via the ledger (no side store, no URL parsing).** A returned `PHAssetResourceUploadJob`
  is mapped back to its key by recomputing `uploadKey(assetLocalIdentifier, resource.type,
  resource.originalFilename)` from the job's own fields; version/attempt come from
  `ledger.entry(key)`. The job's `resource` property supplies the `PHAssetResource` for re-create
  directly — no asset re-fetch, no match.
- **Persisted change-token cursor (App Group).** The discovery cursor moves from in-process to a
  shared App-Group store (an archived `PHPersistentChangeToken` in `NSUserDefaults`), advanced
  **only at the end of a fully-drained cycle**. A short-lived wake no longer re-enumerates the whole
  library; a cap-truncated cycle does not advance, so the next wake re-derives.
- **Cap-aware creation + tri-state result.** On `PHPhotosErrorLimitExceeded` the extension stops
  creating jobs for the cycle, does not advance the token, and returns a `.processing` result so the
  system re-invokes (falling back to `.completed` if the SDK enum lacks `.processing`). `REQUESTED`
  skip on re-derivation prevents duplicate jobs — **no residue store**. `process()` returns a
  tri-state (`completed`/`processing`/`failure`) the Swift shell maps.
- **No staleness sweep.** Assuming the system surfaces every job's terminal result under
  `.retry`/`.acknowledge`, the drain plus the system's unacknowledged-job retention is a complete
  safety net; no time-based re-attempt of `REQUESTED` rows is added.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `sync-engine`: "Resource-changed decision" becomes a **pure query** that skips on `REQUESTED`
  (same version) as well as `COMPLETED`, and writes nothing; a new "Upload-started recording"
  requirement records `REQUESTED` on the new `UploadStarted` event (write-after-act); "Failure
  adjudication" no longer records `REQUESTED` inline (it leaves the ledger at `FAILED` until
  `UploadStarted`). Completion recording, request minting, and provider-failure semantics are
  unchanged.
- `ios-background-upload`: "Drain-all job disposition" is **removed** and replaced by "Completion and
  retry adjudication" (a returned job's key comes from its **destination URL** — `resource` is nil for
  succeeded jobs — and **every presented job is acknowledged**, or iOS errors 50008); "Engine-gated
  real upload-job creation" adopts write-after-act and now records `COMPLETED`; "In-extension
  discovery via persistent change token" persists the token in an App Group store and advances it
  only on a fully-drained cycle; new requirements add cap-aware creation with a tri-state `process()`
  result that also returns `processing` while the ledger has pending rows (to flush completions, as
  the OS invokes the extension lazily), and "Re-provision resets sync state" (a valid config re-scan
  clears the ledger + cursor and re-registers the extension).
- `sync-ledger`: the `LedgerBackend` storage seam gains `clear()` (delete-all + change signal) — the
  one sanctioned app-side ledger reset, used by re-provision.

> **On-device addendum (2026-06-22).** The retention design changed during device verification:
> `job.resource` is nil for succeeded jobs, so the key is read from `job.destination` (not recomputed
> via `uploadKey`); every presented job must be acknowledged (error 50008); ObjC-`nonnull` job fields
> are nil at runtime and must be read into nullable locals; raw-S3 `PUT` works with no `OPTIONS`
> preflight (the resumable-upload TOP RISK did not materialize). All behaviors verified end-to-end on
> a physical device (reset → re-upload → completion accounting → status "backed up").

## Impact

- **Code — engine** (`:domain:engine`): `SyncEngine.decide()` (pure query; skip `REQUESTED`+same
  version; `FAILED`→`Work`), remove the `REQUESTED` write from `decide`/`retry`, add
  `SyncEvent.UploadStarted` + its handler (`recordRequested`). `commonTest` `SyncEngineTest` updated
  (invert the hope-never-skips case, split the decide-records cases, reframe the suffix-convergence
  history to include `UploadStarted`, add a dropped-`UploadStarted` case).
- **Code — extension** (`:app:ios:photokit-extension`): rewrite `UploadCycle` (drain phases:
  adjudicate `.retry` → adjudicate `.acknowledge` → discover; write-after-act `UploadStarted`;
  cap-aware stop) and give it a `LedgerReader` to reconstruct lifecycle events; rewrite
  `IosUploadJobPlatform` (replace `drainJobs` with `fetchRetryJobs`/`fetchAckJobs`,
  `retryWithDestination`, per-job `acknowledge`, capture the `limitExceeded` error, expose the job's
  recomputed key + `state`/`error`/`resource`); back `DiscoveryStore` with App-Group `NSUserDefaults`
  (archived token) behind a `commonMain` port; `UploadExtensionRoot.process()` returns the tri-state;
  `UploadCycleTest` rewritten against a fake platform + in-memory ledger.
- **iOS project / Swift shell**: `BackgroundUploadExtension.swift` maps the Kotlin tri-state to
  `PHBackgroundResourceUploadProcessingResult` (`.completed`/`.processing`/`.failure`), with a
  `.processing`→`.completed` fallback if the case is unavailable on iOS 26.1.
- **Docs**: `design.md §2.2` rewritten — the "REQUESTED is a hope" narrative becomes "`ResourceChanged`
  is a pure query; the three lifecycle events are the only writers; an in-flight `REQUESTED` is
  skipped because the system surfaces every job's result."
- **On-device verify (gates merge)**: `job.resource` is non-null on a fetched job and accepted by
  `creationRequestForJob`; first failure surfaces under `.retry`, retry-spent under `.acknowledge`
  with `state == Failed`; `limitExceeded` surfaces from the `createJob` error out-param; the
  `.processing` result case exists; an archived `PHPersistentChangeToken` round-trips through
  App-Group `NSUserDefaults`.
- **No change to**: `:capability:s3` (the presigner/provider — retry re-mints via the existing
  `provide`; no inverse method needed), `:capability:config`, `s3-request-provider`, `sync-ledger`
  (the schema already carries `state`/`attempt`/`version`/`updatedAt`), `sync-status`/the status
  screen (it already projects `COMPLETED`; completion accounting makes it real for free), the
  delivery/CI path.
