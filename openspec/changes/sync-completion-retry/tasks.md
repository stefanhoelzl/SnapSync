## 1. Engine: ledger-authoritative model (`:domain:engine`, `commonMain` + `commonTest`)

- [x] 1.1 Add `SyncEvent.UploadStarted(job: UploadJob)` to the event sealed interface (the platform's
  "I created the job" observation, symmetric with `UploadFailed`/`UploadCompleted`).
- [x] 1.2 `SyncEngine.decide(resource)` → pure query: `COMPLETED`/`REQUESTED` + same version →
  `AlreadyUploaded`; `FAILED`/absent → `Upload(attempt 0)`; `COMPLETED`/`REQUESTED` + differing
  version → `ReUpload(attempt 0)`. Mint the request for `Work`, but **do not** call `recordRequested`.
- [x] 1.3 `handle(UploadStarted(job))` → `recordRequested(key, job.attempt, version)`; answer
  `AlreadyUploaded`. Make this the **only** site that records `REQUESTED`.
- [x] 1.4 `retry(UploadFailed)` → record `FAILED` only (drop the inline `recordRequested`); still
  return `Retry(attempt+1, freshly minted request)`. The retry's `REQUESTED` now comes via
  `UploadStarted`.
- [x] 1.5 Update `SyncEngine` logging at the dispatch seam for the new event (INFO `UploadStarted`
  key+attempt); keep decision methods pure.
- [x] 1.6 `SyncEngineTest`: invert "a hope never skips" → "in-flight (REQUESTED) same-version skips"
  (must include an `UploadStarted` to establish `REQUESTED`); split "unknown resource uploads" (decide
  writes nothing; `UploadStarted` records); add "FAILED re-uploads", "REQUESTED differing version
  re-uploads", "dropped UploadStarted is re-issued"; adjust the retry test (ledger rests at `FAILED`
  until `UploadStarted`); reframe the suffix-convergence history to include `UploadStarted` events.
- [x] 1.7 `./gradlew build` green (JVM tests + iOS metadata compile).

## 2. Discovery cursor: persisted port (`:app:ios:photokit-extension`)

- [x] 2.1 Turn `DiscoveryStore` into a `commonMain` port over opaque token bytes
  (`loadToken(): ByteArray?` / `saveToken(ByteArray)`); add an in-memory fake in `commonTest`.
- [x] 2.2 `iosMain` impl: archive/unarchive `PHPersistentChangeToken` (NSSecureCoding) ↔ `ByteArray`,
  stored in App-Group `NSUserDefaults(suiteName = "group.app.snapsync")`.
- [x] 2.3 In `IosUploadJobPlatform.discoverResources`, stop saving the token inline; return the
  discovered resources and let `UploadCycle` decide when to advance (gated on a fully-drained cycle).

## 3. Drain adjudication + cap handling (`:app:ios:photokit-extension`)

- [x] 3.1 Widen the `UploadJobPlatform` port: replace `drainJobs()` with `fetchRetryJobs()` /
  `fetchAckJobs()` returning a platform-neutral job view (recomputed `key`, `state`, mapped
  `UploadError?`, opaque `resource` payload), plus `retryJob(job, request)`, `acknowledge(job)`, and
  a `createJob` that signals `limitExceeded` distinctly (e.g. a sealed result).
- [x] 3.2 `IosUploadJobPlatform`: implement the above over `fetchJobsWithAction(.retry|.acknowledge)`,
  `PHAssetResourceUploadJobChangeRequest` (`retryWithDestination:`, `acknowledge`),
  `creationRequestForJob`; recompute the key via `uploadKey(job.assetLocalIdentifier.replace('/','_'),
  job.resource.type, job.resource.originalFilename)`; map `job.state`/`job.error`; capture the
  `performChangesAndWait` error out-param and classify `PHPhotosErrorLimitExceeded`.
- [x] 3.3 Give `UploadCycle` a `LedgerReader` (to reconstruct lifecycle `UploadJob`s: resource from
  the job's payload, version/attempt from `ledger.entry(key)`); keep the engine the single writer.
- [x] 3.4 Rewrite `UploadCycle.run()`: (a) `.retry` phase → `UploadFailed` → `retryJob(freshURL)` →
  `UploadStarted`; (b) `.acknowledge` phase → `Succeeded`→`UploadCompleted`+ack / already-COMPLETED→ack
  / spent-failure→`UploadFailed`+re-create(`job.resource`)+ack-on-success+`UploadStarted`,
  leave-unacked on cap; (c) discovery phase → `ResourceChanged`→`Work`→`createJob`→`UploadStarted`,
  stop on `limitExceeded`; (d) advance/persist the token only if no cap was hit; (e) return the
  tri-state result.
- [x] 3.5 `UploadExtensionRoot.process()` → return the tri-state (`completed`/`processing`/`failure`)
  instead of `Bool`; keep the config-absent path a clean `completed` no-op.
- [x] 3.6 Rewrite `UploadCycleTest` against the fake platform + in-memory ledger: completion records
  `COMPLETED`; first failure retries with a fresh URL; spent failure re-creates from the job resource;
  cap during discovery → not advanced + processing result; cap during re-create → job left unacked;
  re-derivation skips in-flight (`REQUESTED`).
- [x] 3.7 `./gradlew compileIosMainKotlinMetadata` green (Linux proxy); simulator tests run on macOS CI.

## 4. Swift shell (`iosApp/`)

- [x] 4.1 `BackgroundUploadExtension.swift`: map the Kotlin tri-state to
  `PHBackgroundResourceUploadProcessingResult` (`.completed`/`.processing`/`.failure`), with a
  `.processing`→`.completed` fallback if the case is unavailable on the installed SDK.

## 5. Docs

- [x] 5.1 Rewrite `design.md §2.2`: `ResourceChanged` is a pure query; the three lifecycle events
  (`UploadStarted`/`UploadFailed`/`UploadCompleted`) are the only ledger writers (unconditional
  upserts); an in-flight `REQUESTED` is skipped; write-after-act + the system-surfaces-all assumption
  give crash-safety without a residue store or staleness sweep. Update `§3.3` flow to the
  adjudicate→discover order, persisted cursor, and tri-state result.

## 6. On-device verification (gates merge)

- [ ] 6.1 Confirm `job.resource` is non-null on a job fetched in a *later* process than its creation,
  and is accepted by `creationRequestForJob` for a re-create. (Fallback: `assetLocalIdentifier`
  re-fetch + key-match.)
- [ ] 6.2 Confirm a first failure surfaces under `.retry`, and a retry-spent failure under
  `.acknowledge` with `state == Failed`; confirm `retryWithDestination` is the single system retry.
- [ ] 6.3 Confirm `creationRequestForJob` surfaces `PHPhotosErrorLimitExceeded` via the
  `performChangesAndWait` error out-param when the cap is exceeded.
- [ ] 6.4 Confirm `PHBackgroundResourceUploadProcessingResult.processing` exists on the device SDK
  (else the Swift fallback to `.completed` is exercised).
- [ ] 6.5 Confirm an archived `PHPersistentChangeToken` round-trips through App-Group `NSUserDefaults`
  and `fetchPersistentChanges(since:)` accepts the restored token.
- [ ] 6.6 End-to-end on device (via the `local-s3.sh` rig): trigger sync → objects land **and** the
  status screen reaches "backed up"; force a transient failure → observe a retry then completion;
  exceed the cap with a large library → observe multi-cycle drain with no duplicate objects.
