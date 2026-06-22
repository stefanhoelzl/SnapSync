## Context

`real-s3-upload` left the extension at **drain-all**: discover → engine `Work` → create a real
presigned job → acknowledge **every** returned job without inspecting it. The ledger only ever holds
`REQUESTED`, so `LedgerSyncStatusSource` (which projects `COMPLETED`/`pending` aggregates) never shows
progress, and a failed upload is never retried (only re-attempted if its asset changes or the change
token expires). This change turns the drain into a real completion/retry engine, persists the
discovery cursor, and handles the system's in-flight job cap.

The enabling discovery (from the iOS 26.1 `Photos` klib metadata) is the shape of
`PHAssetResourceUploadJob`: a returned job exposes `destination` (the `NSURLRequest` we set),
`assetLocalIdentifier`, `resource` (**the `PHAssetResource` itself**), `state`
(`Registered`/`Pending`/`Failed`/`Succeeded`/`Cancelled`), `error`, and `responseHeaderFields`; the
change request offers `acknowledge`, `retryWithDestination:` (one retry), and `cancel`; jobs are
fetched by action via `fetchJobsWithAction(.retry | .acknowledge)`. `PHPhotosErrorLimitExceeded` and
`PHPhotosErrorPersistentChangeTokenExpired` both exist. The result enum
`PHBackgroundResourceUploadProcessingResult` is **not** in the Kotlin/Native bindings (the protocol
conformance is Swift), so the tri-state lives in Swift and Kotlin returns a value it maps.

## Goals / Non-Goals

**Goals:**
- The ledger reaches `COMPLETED` for uploaded resources, so the status screen becomes real with no
  `sync-status` change.
- Failed uploads retry forever (the engine's existing policy), promptly, driven by the drain.
- Short-lived extension wakes stop re-enumerating the whole library (persisted cursor).
- A backlog larger than the system's job cap drains across cycles with **no lost and no duplicate**
  work, without a persisted residue list.

**Non-Goals:**
- Reconstruction `x-amz-meta-*` headers / the asset-metadata layer (still out of scope; `metadata`
  stays empty).
- Migration to the iOS 27 async `PHBackgroundResourceUploadJobExtension` (`process()` stays the
  iOS 26.1 sync entry; only its return becomes tri-state).
- An attempt budget / give-up policy — retry-forever is retained; `failed` stays `0` in the status
  projection.
- A staleness sweep of stuck `REQUESTED` rows (see D8).

## Decisions

### D1: The ledger is authoritative for in-flight work — `decide()` skips on `REQUESTED`
`decide(ResourceChanged)` answers: `COMPLETED`/`REQUESTED` with `version == resource.version` →
`AlreadyUploaded`; `FAILED`, absent, or differing version → `Work` (`Upload`/`ReUpload`, `attempt 0`).
**Why:** with `REQUESTED` meaning "a job is in flight, skip it," re-deriving the change feed is
**idempotent** — a cap-truncated cycle simply re-derives next time and the in-flight resources are
skipped, so no persisted residue list is needed (D6). **Reverses** the prior deliberate rule
(`design.md §2.2`) that a `REQUESTED` "hope" never skips. That rule existed for crash-safety; D2 buys
the safety back differently.

### D2: Write-after-act — `REQUESTED` is recorded only after the job exists, via `UploadStarted`
`decide()` becomes a **pure query** (reads the ledger, mints the request, writes nothing). A new
platform observation event `SyncEvent.UploadStarted(job)` is reported **after** `createJob`/`retry`
succeeds, and *that* records `REQUESTED`. **Why:** skip-on-`REQUESTED` (D1) is only sound if
`REQUESTED` implies a real job. Recording before the act (today) would let a crash between record and
create strand a photo (skipped forever). Recording after the act makes the crash window degrade to a
*bounded, idempotent duplicate*: re-derivation finds no ledger row → `Work` → creates again → one
extra upload, idempotent at the S3 destination. **Alternative rejected:** keep record-before-act and
add a `updatedAt`-based staleness sweep to rescue stranded `REQUESTED` rows — heavier, needs a
ledger-driven re-emit source, and unnecessary under the system-surfaces-all assumption (D8).

### D3: `ResourceChanged` is a query; the three lifecycle events are the only writers
After D2, the ledger changes only on `UploadStarted`→`REQUESTED`, `UploadFailed`→`FAILED`,
`UploadCompleted`→`COMPLETED` — each an **unconditional idempotent upsert** of `(state, attempt,
version)`. **Why this is safe:** the suffix-convergence property (replay any suffix → same ledger
state) is *preserved and simplified* — every suffix ends at the same final event, and unconditional
upserts make the final write determine the state regardless of where replay begins. `ResourceChanged`
contributes no write, so it cannot diverge. This is the new `design.md §2.2` framing.

### D4: Retention via the ledger + the job's own fields — no URL parsing, no side store
A returned job is mapped back to its ledger key by **recomputing** `uploadKey(assetId =
job.assetLocalIdentifier.replace('/','_'), resourceType = job.resource.type, originalFilename =
job.resource.originalFilename)` — the *same* `uploadKey` function discovery uses (one unit-tested
source of truth). Version and attempt come from `ledger.entry(key)`. **Why not parse
`job.destination`:** it would duplicate the provider's private key layout (`resources/` prefix,
bucket placement, percent-encoding) across a module boundary — a silent-drift hazard (a changed
prefix still signs valid uploads but breaks completion-matching → endless re-upload). Recompute uses
the job's own asset/resource facts instead. **Bonus:** `job.resource` *is* the `PHAssetResource`, so
the exhausted-retry re-create calls `createJob(freshRequest, job.resource)` directly — no asset
re-fetch, no resource-match. The `:capability:s3` provider is untouched (retry re-mints via the
existing `provide`).

### D5: The drain is the retry engine; the system's job retention is the safety net
Per cycle, **before** discovery, the drain runs two phases:
- **`.retry` set (first failures):** recompute key → reconstruct the `UploadJob` (resource from
  `job.resource`, version/attempt from the ledger) → `engine.handle(UploadFailed)` (records `FAILED`,
  returns `Retry` with a freshly presigned URL) → `retryWithDestination(url)` →
  `engine.handle(UploadStarted)` (records `REQUESTED`, `attempt+1`).
- **`.acknowledge` set (terminal):** `Succeeded` → `UploadCompleted` (records `COMPLETED`) →
  `acknowledge`. Already-`COMPLETED` in the ledger → `acknowledge` (idempotent no-op). `Failed`/
  `Cancelled` (retry spent) → `UploadFailed` (records `FAILED`) → `createJob(freshURL, job.resource)`
  → **acknowledge only on success**; on `limitExceeded` leave it un-acknowledged so the system
  re-presents it next cycle (the system's retention is the retry "residue" — no extra store) →
  `UploadStarted`.

`UploadCycle` gains a `LedgerReader` to reconstruct the lifecycle `UploadJob`s; the engine remains
the single writer (single-writer invariant intact). `UploadFailed` carries a reconstructed `Resource`
because the engine re-mints the retry request via `provide(resource)`.

### D6: Cap handling needs no residue store
On `PHPhotosErrorLimitExceeded` from `createJob`, the extension stops creating jobs for the cycle and
does **not** advance the change token. Next wake re-derives the same change set; D1's `REQUESTED`-skip
filters the already-created jobs, so only the un-created remainder is created. The
discovered-but-not-yet-created resources are protected purely by **"don't advance the token on a
cap-truncated cycle"** — they have no job and no ledger row, so the token is their only guard. This is
the single load-bearing token rule (the system's job retention only rescues jobs that were actually
created).

### D7: Persisted change-token cursor in an App-Group store — efficiency only
`DiscoveryStore` becomes a `commonMain` port (`loadToken(): ByteArray?` / `saveToken(ByteArray)`)
with an `iosMain` `NSUserDefaults(suiteName: "group.app.snapsync")` implementation that archives the
`PHPersistentChangeToken` (NSSecureCoding) to `Data`. The token is saved **only at the end of a
fully-drained cycle** (no `limitExceeded`). **Why a port:** the cursor/advance orchestration stays in
`UploadCycle` (testable on the simulator with a fake store); the `NSUserDefaults` glue is untested
iosMain wiring, per the module's norms. **Correctness note:** under D1 a cold-start full
re-enumeration is already correct (everything in flight is `REQUESTED`-skipped); persistence is purely
to avoid re-enumerating the whole library on every short-lived wake.

### D8: No staleness sweep — assume the system surfaces every job's terminal result
We assume every created job eventually appears under `.retry` or `.acknowledge`. Then every `FAILED`
row corresponds to a system job we have not yet acknowledged (we acknowledge only after a successful
re-create), so it re-presents and the drain re-handles it — nothing leaks out of the drain's reach.
Consequently: no `updatedAt`-based re-attempt of `REQUESTED` rows is added, and the engine's general
`FAILED → Work` discovery rule is retained for correctness but is **effectively dead on iOS** (the
drain re-creates a failed row back to `REQUESTED` before discovery runs). If on-device observation
ever shows silent drops, the deferred mitigation is the staleness sweep from D2's rejected
alternative.

### D9: Tri-state `process()` result in Swift, with a `.processing` fallback
Kotlin `UploadExtensionRoot.process()` returns `COMPLETED` / `PROCESSING` / `FAILED`; the Swift shell
maps to `PHBackgroundResourceUploadProcessingResult.completed`/`.processing`/`.failure`. Because
`.processing` existence on iOS 26.1 is unverifiable from Linux, the Swift mapping falls back to
`.completed` if the case is absent — residue-free re-derivation (D6) still drains on the next
system-scheduled wake, so `.processing` only affects promptness, not correctness.

## Risks / Trade-offs

- **`job.resource` may be null or rejected by `creationRequestForJob` on a fetched (cross-process)
  job.** → Device-verify first; the metadata strongly implies the property exists. Fallback: re-fetch
  via `assetLocalIdentifier` + `assetResourcesForAsset` and match by recomputed key (the original,
  uglier path).
- **`.retry` vs `.acknowledge` state semantics unverified** (does a first failure really surface under
  `.retry`, retry-spent under `.acknowledge` with `state == Failed`?). → Device-verify; the
  adjudication branches key off `state`, so a wrong assumption is observable in `idevicesyslog`.
- **`.processing` may not exist on iOS 26.1.** → D9 fallback to `.completed`; correctness holds.
- **The system-surfaces-all assumption (D8) may be false** (silent job drops). → Then a stranded
  `REQUESTED` photo is unrecovered until token expiry; mitigation is the deferred staleness sweep.
  Explicitly accepted for v1.
- **Modify-during-upload edge:** an asset changed while its job is in flight records `COMPLETED` for
  the in-flight generation's version; the next discovery sees a differing version → one redundant
  re-upload. Self-heals; accepted (inherent to ledger-as-retention, not a per-generation map).
- **Engine semantics change blast radius** is contained: the only runtime engine driver is
  `UploadCycle` (no engine console exists yet); the rest is `SyncEngineTest` + `sync-engine` spec +
  `design.md §2.2`.

## Migration Plan

1. **Engine first** (pure Kotlin, JVM + simulator `commonTest`): `decide()` pure-query + skip-
   `REQUESTED`; add `UploadStarted` + handler; drop the `REQUESTED` write from `decide`/`retry`;
   update/extend `SyncEngineTest`. Verify `./gradlew build`.
2. **Discovery cursor port** (`commonMain` `DiscoveryStore` port + fake; `iosMain` `NSUserDefaults`
   impl) and token-advance gating in `UploadCycle`.
3. **Drain adjudication** (`UploadCycle` phases + `LedgerReader`; `IosUploadJobPlatform` fetch/retry/
   ack/limit/`job.resource` surface; tri-state `process()`); rewrite `UploadCycleTest`. Verify
   `./gradlew compileIosMainKotlinMetadata` (Linux proxy) + simulator tests on macOS CI.
4. **Swift shell** tri-state mapping with `.processing` fallback.
5. **Docs**: rewrite `design.md §2.2`.
6. **On-device verify**: sideload a dev IPA (`local-s3.sh` rig), trigger sync, confirm objects land
   **and** the status screen reaches "backed up" (the new positive signal); force a failure (e.g.
   wrong creds briefly) and observe a retry; exceed the cap with a large library and observe
   multi-cycle drain. **Rollback:** revert the branch — `real-s3-upload` drain-all is restored.

## Open Questions

- Does `job.resource` survive across the process boundary (a job created last wake, fetched this
  wake)? If not, fall back to `assetLocalIdentifier` re-fetch.
- Does `retryWithDestination` count as the *only* retry (so a second failure surfaces under
  `.acknowledge`), as assumed? Confirm on device.
- Should `UploadStarted` answer a dedicated `SyncDecision.NoOp` rather than reusing `AlreadyUploaded`?
  (Cosmetic; the platform ignores the return. Decided: reuse `AlreadyUploaded` = "nothing to do.")
