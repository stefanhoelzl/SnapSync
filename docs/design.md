# SnapSync v1 — Design

A deliberately **simpler resurrection** of SnapSync. The previous incarnation was a
zero-knowledge, encrypted, multi-device photo-**sharing** app (events, QR invitations, deep
links, Android + iOS native SwiftUI, Appium e2e). v1 collapses that to a **personal one-way
gallery backup** to an S3 bucket, iOS-only, shipped via TestFlight.

---

## 1. Scope

**In scope (v1):**
- iOS app, distributed via **TestFlight** only. **Minimum iOS 27.0.**
- **One-way upload** (backup): new local photos → S3. Never downloads, never deletes remotely.
- **Photo assets, whole library** — **all `PHAssetResource`s** of each (original + edits +
  adjustments + Live Photo paired video). Standalone *video assets* are out of scope for v1.
- **Background uploads via `PHBackgroundResourceUploadJobExtension`** (iOS 27+) — the system
  schedules and **performs** the uploads on the app's behalf, even when suspended/locked. (See §3.)
- S3 target configured **at build time**.
- A **local desktop (JVM) test app** — phone-frame preview + side-by-side control panel
  (display overrides + engine console; §5.1).

**Explicit non-goals / deferred:**
- No Android app yet (architecture keeps the door open).
- No encryption in v1 (plaintext upload; client-side encryption is a later concern).
- No bidirectional sync, no remote deletes, no content-dedup (see §3.1 — metadata keys).
- No video, no album selection, no settings screen.
- No CI/CD or TestFlight automation decided yet (parked).

> **API note.** iOS 26.1 introduced `PHBackgroundResourceUploadExtension`; iOS 27 **replaced it**
> with `PHBackgroundResourceUploadJobExtension` (and deprecated the 26.1 one). We target the iOS 27
> Job API. iOS 27 is in beta as of mid-2026, so this is bleeding-edge and tested on a 27 device.

---

## 2. Architecture overview

**Single Compose Multiplatform codebase** (hard requirement) renders both the iOS app and the
desktop test app. Layering, top to bottom:

- **UI** — Compose, written against an in-house **design-system abstraction** (so the skin is swappable).
- **Platform-independent backend** — the sync vocabulary, the ledgered **decision core**
  (`SyncEngine` + its SQL ledger, the engine's only state), the status projection (read-only
  ledger queries), and the MVI presentation layer. Knows nothing about the platform.
- **Platform adapters** — own discovery, upload execution, and small lossy-tolerant bookkeeping;
  they **drive** the shared decision core with observation events and act on the decisions it
  returns (§2.2). Upload memory lives in the engine's ledger, not the platform.

Implementations are selected by **dependency injection in the app modules** (manual composition
root), *not* `expect`/`actual`. Rationale: the JVM target needs **multiple** impls of the same
seam (in-memory fake for tests, the controllable fake for the desktop app), which `expect`/`actual`
(one impl per compile target) cannot express; a plain `interface` + DI can.

The iOS extension's `processJobs()` **drives** the shared `:domain:engine` decision core with events;
the desktop test app drives that identical core from an engine console (§5.1), so every shared
decision path is exercised off-device. Orchestration (loops, backpressure, job execution) is
deliberately **platform-side** — the shared core decides and remembers.

### 2.1 Module graph

```
:domain:engine         the shared sync vocabulary + logic; no platform deps:
                         • platform seam (§2.2), resources-only: Resource (concrete value type
                           with opaque platform payload + version), SyncEvent, SyncDecision,
                           UploadJob, UploadRequest, UploadError, UploadRequestProvider.
                           (Asset layer = later slice above.)
                         • SyncEngine — the ledgered decision core (§2.2) + its ledger
                           (LedgerBackend storage seam, LedgerReader/LedgerWriter/LedgerWatcher,
                           SQLDelight)
:domain:status         → :domain:engine + :domain:permission (BOTH implementation-scoped — neither
                         leaks to status's consumers). The status projection (§2.4): SyncStatus +
                         SyncState + SyncStatusSource (snapshot seam, §2.3) and
                         LedgerSyncStatusSource — ledger aggregates × permission → snapshots.
:domain:permission     PermissionStatus / PermissionStatusSource / PermissionRequester.
:domain:presentation   → :domain:status + :domain:permission. Orbit MVI container(s) + UiState.
                         COMPOSE-FREE. NO engine dependency — engine types never reach
                         presentation's compile classpath.
:domain:ui             → :domain:presentation + :domain:ui:components. Compose screens, written
                         exclusively against the App* design system.
:domain:ui:components  semantic App* components + the Material 3 skin — the ONLY module allowed to
                         import Material 3 (§5).

:app:desktop           desktop harness: phone-frame preview + control panel (display overrides today,
                       engine console later — §5.1). The JVM platform pieces (EngineConsole core,
                       DumbHttpRequestProvider) land with the platform-integration slices.
:app:ios               (later) wires the iOS adapter + Darwin Ktor engine + framework export → iosApp/

iosApp/                Xcode project (not Gradle): the app target (Swift host + Info.plist) AND a
                       PHBackgroundResourceUploadJobExtension target (Generic Extension; extension
                       point com.apple.photos.background-upload; BackgroundUploadURLBase = bucket
                       host). The :app:ios framework is embedded in BOTH targets (the extension needs
                       presign/SigV4 + config). App and extension share an App Group container.
```

Dependency flow: `:domain:engine ← status ← presentation ← ui`; app modules wire the concrete platform
adapters. Every boundary is **compiler-enforced**, and the backend swap is **structural**
(`:app:ios` wires the PhotoKit-backed adapter; the desktop wires the engine console).

The formerly planned `:capability:*` modules are **dissolved** (2026-06-11): gallery/uploader became
the event seam below (discovery and upload execution are platform-adapter internals); `:capability:s3`
returns later as one `UploadRequestProvider` implementation; `:capability:store` dissolved into
**platform-private persistence** (the iOS App-Group bookkeeping is an adapter detail, §2.4/§3.3).

### 2.2 Platform seam: event → decision core (ledgered)

**The platform drives, the engine decides** (decided 2026-06-11; **ledgered** 2026-06-12,
replacing the stateless core). Platform adapters (iOS extension, desktop engine console, Android
later) observe the world — discovered/changed resources, failed uploads, completed uploads —
report each observation to the shared core as an **event**, and act on the **decision** the core
answers with. **Events are observations, never bookkeeping** ("trust events as observations, not
as bookkeeping"): platforms do not filter, dedupe, or track what was uploaded — the engine owns
that in its **ledger**, a SQL-backed per-key store written exclusively by the engine. Rationale
(2026-06-12 research): change-token expiry is routine and Apple's remedy is full re-enumeration —
without an uploaded-memory that re-uploads the whole library; and Apple's own upload-job guidance
prescribes write-then-acknowledge with per-key idempotent tracking (exactly-once across the file
system and the job system is impossible — reports are at-least-once by construction). The ledger
consolidates what would otherwise be three platform-side stores (status fold, discovery ledger,
event inbox) into one shared, desktop-testable one.

**The sync domain knows only resources** (decided 2026-06-12). Since only resources are ever
transported, the engine's vocabulary stops there: the asset→resource fan-out, the **filename
layout** (`<cloudId>-<kind>.<ext>` encodes asset identity), and asset-metadata duplication all
belong to a **later asset layer above this seam** (platform-side until that layer exists). The
platform hands each resource a single opaque `filename` — pure *identity*, a plain string. Its
*representation* (percent-encoding into a URL path, a placement prefix like `photos/`, or even a
header on transports that carry identity differently) is **the provider's responsibility**, under
one contract: the filename→destination mapping must be **deterministic and injective** — that is
where upload idempotency lives.

```kotlin
class Resource(                              // concrete domain type, platform-constructed
    val filename: String,                    // identity; layout is the platform/asset layer's
    val contentType: String,                 //   (iOS: "<cloudId>-<kind>.<ext>")
    val version: String,                     // content-identity proof (iOS: asset modificationDate);
                                             //   engine compares EQUALITY ONLY, never parses
    val metadata: Map<String, String>,       // opaque to the engine, becomes headers
    val data: Any,                           // opaque platform payload: PHAssetResource, bytes,
)                                            //   file path… always present; engine and provider
                                             //   NEVER read it — only the platform that wrote it
                                             //   does, at its execution edge. Deliberately Any,
                                             //   not a generic: a type param would infect the
                                             //   seam types and erase to `id` in the ObjC header
                                             //   anyway (writer == reader, risk is contained).

sealed interface SyncEvent {                 // observations, at-least-once, never bookkeeping
    class ResourceChanged(val resource: Resource) : SyncEvent
    class UploadFailed(val job: UploadJob, val error: UploadError) : SyncEvent
    class UploadCompleted(val job: UploadJob) : SyncEvent
                                             // reported at the ack edge, BEFORE acknowledge()
}                                            //   (write-then-act: duplicable, never losable)

sealed interface SyncDecision {              // the engine's answer: what, if anything, to do
    sealed interface Work : SyncDecision { val job: UploadJob }
    class Upload(override val job: UploadJob) : Work      // not (provably) uploaded yet
    class ReUpload(override val job: UploadJob) : Work    // completed, but version changed
    class Retry(override val job: UploadJob) : Work       // answer to UploadFailed, attempt + 1
    data object AlreadyUploaded : SyncDecision            // ledger proof: nothing to do
}

class UploadRequest(                         // complete, executable: PUT resource → url
    val url: String,
    val headers: Map<String, String>,        // exactly these headers
    val resource: Resource,                  // rides whole for the failure round-trip
)
class UploadJob(val request: UploadRequest, val attempt: Int)
                                             // attempt: 0 = create platform job, >0 = retry

interface UploadRequestProvider {            // impls: dumb-HTTP (test platform), S3 presigner (later)
    suspend fun provide(resource: Resource): UploadRequest
    // owns encoding AND placement of resource.filename (e.g. percent-encode + "photos/" prefix
    // for S3; other transports may carry identity as a header with different escaping).
    // CONTRACT: filename → destination is deterministic and injective; signs resource.metadata
    // as headers; returns the full request carrying the same resource instance. Called only for
    // Work answers — never on a skip.
}

interface LedgerBackend {                    // storage seam: dumb row store, last write wins
    val changes: Flow<Unit>                  // ding after every put; "re-read the truth" — where
                                             //   another process writes, feeding this is that
                                             //   backend's concern (iOS: Darwin observer)
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)      // single-row upsert = the unit of atomicity
    suspend fun aggregates(): LedgerAggregates  // one snapshot-consistent SQL round-trip
}
open class LedgerReader(backend)             // entry(key) — the per-key read-only face (engine's)
class LedgerWriter(backend, clock = System) : LedgerReader
                                             // recordRequested / recordCompleted / recordFailed;
                                             // stamps updatedAt — the SINGLE stamping point
                                             // (engine clock-free, backends store verbatim).
                                             // ONE writer per platform, by construction: only the
                                             // engine-hosting composition root constructs it
class LedgerWatcher(backend)                 // aggregates: Flow<LedgerAggregates> — cold: current
                                             //   truth on collect, re-query per conflated ding,
                                             //   deduped; the ONLY type surfacing aggregates/dings
class LedgerEntry(key, state /* REQUESTED|COMPLETED|FAILED */, attempt, version, updatedAt)
class LedgerAggregates(pending, completed, newestCompletionAt /* null = never completed */)
                                             // schema: key PRIMARY KEY, state, attempt, version,
                                             // updatedAt (epoch millis; SQLDelight typed columns,
                                             // adapters hidden in one factory)

class SyncEngine(provider: UploadRequestProvider, ledger: LedgerWriter) {
    suspend fun handle(event: SyncEvent): SyncDecision
    // ResourceChanged(r):  ledger absent/REQUESTED/FAILED        → Upload(mint, attempt = 0)
    //                      COMPLETED + version == r.version      → AlreadyUploaded (no mint)
    //                      COMPLETED + version != r.version      → ReUpload(mint, attempt = 0)
    // UploadFailed(j, e):  → Retry(mint, j.attempt + 1) — forever; records FAILED then REQUESTED
    // UploadCompleted(j):  → records COMPLETED, answers AlreadyUploaded (by then literally true)
}

sealed interface UploadError {               // platform maps raw errors in; v1 policy ignores, logs only
    object Network : UploadError
    class Http(val status: Int) : UploadError
    object Cancelled : UploadError
    class Unknown(val detail: String) : UploadError
}
```

**Engine behavior.** A key is skipped **only on proof** — a `COMPLETED` ledger entry with the
same `version`. A `REQUESTED` entry is a **hope** (the engine cannot verify its answer was ever
executed — engine-write vs platform-job-creation is a two-generals pair), and a hope never
justifies skipping: re-submission of unproven work always yields work, because skipping a hope
risks silent permanent loss while a duplicate execution is just an idempotent overwrite at the
provider's injective destination. **Retry forever in v1** — no attempt budget, no give-up; every
retry re-mints, so expired presigned URLs heal. Provider failures **rethrow** from `handle()`
with the **ledger untouched** (the engine records only after minting succeeds); the event counts
as unprocessed and re-handling is safe (idempotent per-key upserts). **Sequential contract:** at
most one `handle()` in flight per engine — all known drivers are sequential loops; concurrency is
the caller's responsibility (a future concurrent driver pays for the guarantee in its own slice;
single-statement upserts already make a future second WAL writer cheap, but cross-process
read-decide-write races would then need re-examination).

**Platform contract.** Report observations at the driver's own pace; on `limitExceeded` simply
stop reporting `ResourceChanged` for the cycle (the engine holds nothing in flight; skips don't
consume job slots). Act on decisions: `Work` → execute the job (`attempt == 0` → create a
platform job; `> 0` → retry the existing one *or* acknowledge-and-recreate — platform's choice;
iOS allows one retry per job); `AlreadyUploaded` → continue. **Report completions at the
acknowledge edge, BEFORE acknowledging** (`UploadCompleted(job)` → then `acknowledge()`) — the
Apple-prescribed write-then-act ordering: a crash between the two re-presents the job and
duplicates the report (absorbed by the ledger) instead of losing it (unrecoverable — after ack
the record is gone from PhotoKit). **Jobs are idempotent instructions:** the platform may skip
executing one when it can prove equivalent work is already submitted. Retention: the platform
must be able to **produce the newest `UploadJob` for each platform job on demand** (iOS: persist
the serializable values — filename, contentType, version, metadata, url/headers, attempt — and
rehydrate the `Resource`, re-attaching its payload via `assetResource(forUploadJob:)`; an
unresolvable handle — asset deleted mid-sync — is acknowledged and dropped, so a payload-less
`Resource` never exists. Minting reads only the string fields, so retries work from the
snapshot). **One ledger writer per platform:** the engine (and its `LedgerWriter`) is hosted
where uploads are decided — on iOS, the extension; the app holds only a read-only ledger view
(justification: the app observes nothing and has nothing to report, not lock safety — see §2.4).
Scope filtering (photos yes, standalone video no) sits above the seam — the engine is
media-type-blind by construction.

**Accepted costs** (eyes open): metadata-only changes that don't move the `version` leave
**stale `x-amz-meta-*` headers** on the remote objects forever (the bytes are right; the headers
aren't — milder than the byte-churn it replaced; a header-refresh job kind is possible future
work). Crash-window duplicate jobs are idempotent overwrites, bounded by the platform's residue
bookkeeping (§3.3). Retry-forever churns a job slot on a permanently-broken resource, and a
terminally-failed resource is only resurrected when its asset changes again.

### 2.3 Sync → presentation seam: state snapshots, not events

`:domain:status` exposes progress to presentation as a **snapshot contract** — `SyncStatus`
(lifetime counts, §2.4) observed via `SyncStatusSource { val status: StateFlow<SyncStatus> }` —
**not** an event stream. Why (decided 2026-06-09):

- **The iOS process topology forbids events.** Uploads run in the extension while the app is
  suspended or dead; the app learns what happened by reading the App Group + job system. The UI is
  inherently a projection of persisted state — no continuous event stream can cross that boundary.
- **The fold lives with the engine.** state = fold(events): whoever integrates events needs engine
  knowledge (retry semantics, `jobLimit`, paging), and the engine must persist its truth anyway. An
  event seam duplicates the fold into presentation — two accumulators with drift risk and no arbiter.
- **Snapshots are self-healing** (every emission is the whole truth): no late-subscriber problem, no
  missed-event corruption, safe conflation (`StateFlow`), and initial render is the same code path
  as any update. Same reason Kubernetes controllers are level-triggered and Compose is `UI = f(state)`.
- Platform signals (`photoLibraryDidChange`, Darwin notifications, foreground entry, polling) are
  **invalidation dings handled inside the iOS impl** — each triggers re-read + a fresh emission;
  none leaks into the contract. "Uploading X of N" is a derivation from counts (the engine can never
  know totals upfront: paged change feed + `jobLimit`).
- One-shot effects (e.g. a "backup completed" toast, later) are derived **downstream** by diffing
  consecutive snapshots in the Orbit container (`postSideEffect`) — the seam stays level-triggered.

### 2.4 Status projection: read-only queries over the engine's ledger

How `SyncStatusSource` (§2.3) gets its truth (re-decided 2026-06-12, implemented in the
status-core change — the ledgered engine supersedes the earlier StatusEvent-fold +
maildir-inbox design). The UI seam stays a level-triggered `StateFlow<SyncStatus>`; behind it,
`LedgerSyncStatusSource` (in `:domain:status`) combines the ledger's `LedgerWatcher` stream with
the permission seam and mints snapshots — constructed via a suspend factory that reads the
current truth first, so the seam's synchronous-first-value promise holds. The ledger signals its
own changes (`LedgerBackend.changes` dings after every put), so no platform plumbs a refresh
trigger: writes ding, the watcher re-queries, the source re-mints.

- **Counts are lifetime aggregates** over ledger rows (`pending` = non-`COMPLETED` keys,
  `completed` = `COMPLETED` keys); `lastFinishedAt` = the newest completion's `updatedAt`.
  `ReUpload` flips a row back to `REQUESTED`, so re-uploads are visible in status (repealing the
  old "invisible re-uploads" cost). `failed` ≡ 0 from the real source (retry-forever never gives
  a key up; an attempt budget fills it in v2) and `estimatedRemaining` ≡ null (v1 never
  estimates) — both fields exist for classification and fakes.
- **Classification is suspended-first** (re-decided at implementation, replacing the
  pending-first table): `!active → SUSPENDED; pending > 0 → IN_PROGRESS; lastFinishedAt == null
  → NEVER_SYNCED; failed > 0 → INCOMPLETE; else COMPLETE`. **There is no FAILED state** — its
  old condition (`completed == 0 && lastFinishedAt != null`) became self-contradictory once
  `lastFinishedAt` means "newest completion"; the whole vertical (SyncState.FAILED,
  UiState.Failed, hero row, harness preset) was deleted. v2's attempt budget can reintroduce it
  with real semantics.
- **`active` is operational state, not a liveness heuristic** (replacing the event-recency
  rule): *backup machinery is allowed to run* — `permission == GRANTED`, derived once inside
  `LedgerSyncStatusSource` (the extension is enabled at grant, v1 has no toggle). Shared logic,
  no clocks, no thresholds. Consequences, accepted: SUSPENDED is preset-only in v1 (the gate
  covers the hero whenever `active` is false), and a wedged-but-enabled pipeline shows
  IN_PROGRESS rather than aging into SUSPENDED.
- **Cross-process topology (iOS):** the extension hosts the engine and the single `LedgerWriter`
  (WAL, short single-statement writes); the app opens the database **read-only** — the
  Apple-documented-safe configuration (`0xdead10cc` applies to write transactions held at
  suspension; the app never writes). The app-side backend feeds its `changes` flow from the
  Darwin notification the extension posts — where dings come from is each backend's
  implementation detail; seam, watcher, and source never know (iOS slice). **Staleness detection
  is read-only:** the app compares the photo library's `currentChangeToken` with the platform
  bookkeeping's last token — mismatch ⇒ undiscovered work ⇒ status can say "waiting for system"
  instead of a false COMPLETE (replaces "foreground catch-up cycles" as the dead-discovery
  mitigation; later slice).
- Uninstall wipes the App Group → status resets to never-synced; re-sync is idempotent
  (accepted).

---

## 3. Sync design

### 3.1 Object keys (metadata-based)

The upload-job API fixes the **destination URL at job-creation time**, but the **system** reads the
bytes during upload — so we never see the bytes and **content-hash keys are impractical** (they'd
force us to read+hash every asset ourselves, defeating the API). v1 uses **per-resource metadata keys**.

We back up **every `PHAssetResource` of each photo asset** (complete backup): `.photo` (original,
immutable), `.fullSizePhoto` (edited render, if any), `.adjustmentData` (edit instructions), alternates,
**and Live Photo `.pairedVideo`/`.fullSizePairedVideo`** (full fidelity, accepted despite "photos
only" — standalone *video assets* remain out of scope).

- **`photos/<encoded filename>`** where the filename is **`<cloudId>-<kind>.<ext>`**, composed
  platform-side (asset-layer knowledge, until that layer exists): `<cloudId>` =
  `PHCloudIdentifier.stringValue` (decided 2026-06-12 — survives backup restores and device
  migrations, so no duplicate trees; the per-device `localIdentifier` never appears in keys);
  `<kind>` = the open platform resource-kind string (e.g. `ios.photo`, `ios.fullSizePhoto`);
  `Content-Type` from the resource's UTI. The filename is pure *identity*; the **S3 provider**
  owns its representation — percent-encoding (bytes outside `[A-Za-z0-9._-]` → `%XX`) and the
  `photos/` placement prefix — under the deterministic-and-injective contract (§2.2; distinct
  filenames never collide). The bucket is **flat** (decided 2026-06-12): asset grouping rides the
  `asset-id` metadata header plus exact filename-prefix LISTs; cheap delimiter-based asset
  enumeration is given up (accepted — restore reads headers anyway).
- **Edit-handling dissolves:** the original resource is immutable (no re-upload churn); an edit just
  **adds** `.fullSizePhoto` + `.adjustmentData` resources, which surface via the change feed as *new*
  jobs under *new* keys. Nothing is overwritten or lost.
- **Reconstruction metadata rides the upload as signed `x-amz-meta-*` headers** on each resource's
  destination request — so it's **as reliable as the bytes, no app turn required** (§3.5): resource-level
  fields (`original-filename`, `resource-type`, `uti`) plus **duplicated asset-level fields** (`asset-id`,
  `created`, `modified`, `location`, `favorite`, `mediaSubtypes`, `pixels`, `duration`). The
  asset↔resources **relationship is the shared `<cloudId>-` filename prefix**, with the
  `asset-id` header as the authoritative grouping. Sign `host` +
  `content-type` + the `x-amz-meta-*` headers; values URL-encoded/base64 (ASCII, ~2 KB cap — fits
  easily). **No sidecar; albums out of scope.**
- Trade-offs (accepted for v1): **no content dedup**. Assets whose cloud identifier is not yet
  resolvable (`identifierNotFound` — e.g. freshly created, not yet reconciled) are **deferred to a
  later cycle**, never keyed by a fallback (a local-id fallback would make keys nondeterministic
  over time → duplicate objects, the exact failure keys exist to prevent). PUT-by-key stays
  idempotent. ⚠ The cloud-identifier choice is **conditional on two on-device verifications**
  (§8): provisional-window determinism and batch-lookup cost in the extension; if either fails,
  fall back to `localIdentifier` keys and re-accept the duplicate-tree-on-restore cost.

### 3.2 Discovery & state (PhotoKit-native discovery, engine-owned memory)

- **Discovery** is the `PHPersistentChangeToken`: `fetchPersistentChanges(since:)` yields change
  **records**, each carrying its own serializable token — so the durable cursor advances **per
  record** (research-verified 2026-06-12; finer than "per batch"). The initial backup has no
  tokens at all (`PHAsset.fetchAssets` over the whole library + capture `currentChangeToken` as
  baseline). **Token expiry is routine** (`persistentChangeTokenExpired`; retention undocumented,
  illustrated in days) — the remedy is full re-enumeration, which is harmless because the
  **engine's ledger** answers `AlreadyUploaded` for everything already backed up (§2.2).
- **State**: upload memory is the **engine's ledger** (the durable per-key store; single writer =
  the extension). The platform keeps only small, **lossy-tolerant** discovery bookkeeping in the
  App Group: `{lastToken, residueIds (rest of a partially-handled record), deferredIds
  (cloudId-unresolved assets)}` — losing the residue costs one record's worth of duplicate jobs,
  absorbed downstream; it exists to keep in-flight work from re-submitting in the *common* path
  (a re-submitted hope is answered with `Upload` again, by design). Retained upload jobs ride
  beside it (§2.2 retention rule). The job system remains the execution authority
  (`acknowledge()` frees `jobLimit` slots). The app never `LIST`s the bucket (`PutObject`-only);
  restore-side `LIST` is a separate admin path (§4).

### 3.3 Flow

**App (foreground):** request `.readWrite` photo authorization → `setUploadJobExtensionEnabled(true)`
→ show status (job states + App-Group progress). **The app uploads nothing itself** — every upload
(resource bytes + their metadata headers) goes through the extension's jobs. (Disable the extension if
the user ever turns backup off.)

**Extension — `processJobs()` (system-invoked, hosts the shared `SyncEngine` + `LedgerWriter`):**
the system downloads each resource (incl. from iCloud) and performs `job.destination` with the
**resource bytes as the request body**. We only manage the queue, in three phases:
1. **Adjudicate failures** — `fetchJobs(action: .retry)`; produce the retained `UploadJob`
   (rehydrate a snapshot `Resource` from the persisted values), map the error → `UploadError`,
   report `UploadFailed(job, error)`; the engine answers `Retry` (`attempt + 1`, freshly
   presigned request) and records `FAILED`+`REQUESTED` in its ledger. ⚠️ **One retry per system
   job only** (`retry` requires failed + unacknowledged + *not previously retried*): first
   failure → `retry(destination: fresh URL)`; already-retried → `acknowledge()` + re-create a
   fresh system job from the same `UploadJob`. Update the retained job.
2. **Acknowledge completed** — `fetchJobs(action: .acknowledge)`; report
   `UploadCompleted(retainedJob)` (the engine records `COMPLETED` — write-then-act), **then**
   `acknowledge()` to free a slot and prune the retention map. A crash between the two
   re-presents the job next cycle → a duplicate report, absorbed by the idempotent ledger.
3. **Create new jobs** — discovery: initial run enumerates the whole library
   (`PHAsset.fetchAssets`); steady state uses `fetchPersistentChanges(since: token)`, advancing
   the persisted token **per change record**; after `persistentChangeTokenExpired`, re-enumerate
   from scratch — the ledger skips everything already done. **Batch-resolve cloud identifiers**
   for the cycle's changed assets (`cloudIdentifierMappings(forLocalIdentifiers:)` — "very
   expensive, use sparingly for batch lookup" per Apple, so exactly once per cycle);
   `identifierNotFound` → leave that asset in the persisted **deferred set** for a later cycle.
   For each resolved asset, expand to its `PHAssetResource`s and wrap each as a `Resource` —
   filename `"<cloudId>-<kind>.<ext>"`, `version` = the asset's `modificationDate`, metadata =
   asset facts merged with resource facts (asset-layer duties, done platform-side until that
   layer exists). Report `ResourceChanged` per resource and act on the decision:
   `AlreadyUploaded` → continue (costs no job slot); `Work` → take the `PHAssetResource` from
   `decision.job.request.resource.data`, build the `URLRequest` from the request (PUT +
   `Content-Type` + signed `x-amz-meta-*`) → `creationRequestForJob(destination:resource:)` in
   `performChanges {}`, tracking `placeholderForCreatedAssetResourceUploadJob`. On
   `limitExceeded` stop reporting for this cycle, persist the **residue** (the record's
   not-yet-jobbed identifiers) beside the token, and return `.processing` — re-entry resumes
   from the residue instead of re-submitting in-flight hopes (lost residue = one record of
   duplicate jobs, absorbed).
4. Return `.completed` (drained → system monitors) / `.processing` (call me again) / `.failure`.
   `willTerminate()` cancels the in-flight collection; an unadvanced token/residue makes the next
   wake resume identically to a limit-hit (re-handling events is always safe).

**Presigned-URL expiry:** the system may run a job much later, so a presigned URL minted at creation
can expire. Use a **long expiry** (SigV4 presigned allows up to 7 days), and the **retry path
refreshes** the destination with a fresh URL. (Header-based SigV4 is unsuitable — it's only valid
~15 min.) `UNSIGNED-PAYLOAD` is used so signing needs no body.

> ⚠️ **TOP RISK — resumable-upload preflight vs raw S3.** The system supports the IETF Resumable
> Upload draft and may send an **`OPTIONS` preflight** to the destination, expecting `200 + Upload-Limit`
> or **`501 Not Implemented`**. Raw S3 (presigned `PUT`) won't answer that contract, and it's
> **unverified** whether the system then falls back to a plain `PUT` or fails. If it doesn't fall back,
> a **tiny edge endpoint** (CloudFront Function / Lambda@Edge / API Gateway) that answers `OPTIONS` and
> proxies `PUT` to S3 may be required — denting the "no backend" goal. **Spike this first.**
>
> ⚠️ **Also verify in Xcode / on device** (iOS 27 API): that the system accepts a **query-string
> presigned URL** destination under `BackgroundUploadURLBase`; required **entitlements**; the exact
> `PHAssetResource` ↔ job ↔ key association; and `creationRequestForJob` semantics.

### 3.4 Why this shape

iOS 27's `PHBackgroundResourceUploadJobExtension` lets the **OS schedule and perform** uploads
(power/network-aware, across suspension/lock) — dissolving temp-file handling, throttling, manual
`URLSession`, and the periodic-trigger problem. We contribute only: **which assets** (change feed),
**where** (presigned destination URL minted in shared Kotlin via hand-rolled SigV4 — the AWS SDK for
Kotlin has no iOS/Native support), and **retry/acknowledge** policy. The extension links the shared
framework and reads embedded IAM config from the App Group.

### 3.5 Reconstruction metadata (for restore) — no sidecar

The backup is made reproducible **without any sidecar or app-side upload** — everything rides the
system's reliable resource uploads as **signed `x-amz-meta-*` headers** (§3.1):

- **Resource-level** fields on each object: `original-filename`, `resource-type`, `uti`.
- **Asset-level** fields, **duplicated onto every resource object** of the asset: `asset-id`,
  `created`, `modified`, `location`, `favorite`, `mediaSubtypes`, `pixels`, `duration`. Duplication is
  tiny and means **any** uploaded resource carries the full asset facts (robust against partial uploads).
- **Relationship is implicit:** all objects whose filename starts with `<cloudId>-` are one asset
  (the `asset-id` header is authoritative). Restore (admin
  creds) does `LIST` with prefix `photos/<encoded cloudId>-` → that's the complete resource set → reads asset-level meta from
  any object → rebuilds via `PHAssetCreationRequest.addResource(...)` + `PHAssetChangeRequest` for
  metadata (best-effort; `localIdentifier` can't be forced).

Why no sidecar: a sidecar would have to be uploaded by the **app** (the extension can't upload
arbitrary blobs, nor wake the app), making it **app-turn-dependent** — you could end up with all the
resource bytes uploaded but manifests missing. Putting reconstruction metadata on the resource
headers removes that failure mode entirely. **The app uploads nothing.**

**Out of scope:** manual user-album membership (organizational, mutable, would only ride headers as a
frozen-at-upload snapshot or need an app-turn-dependent store — cosmetic, deferred). Smart albums
re-derive from properties on restore, so they need nothing.

> Restore is a **separate admin-credentialed tool** (needs `s3:ListBucket` + `s3:GetObject`), never the
> shippable app (which is `PutObject`-only). Out of scope for v1 beyond ensuring the data suffices.

---

## 4. S3, auth & config

- **Auth: long-lived IAM access key/secret embedded in the build** (extractable from the IPA —
  accepted for a private TestFlight tool).
- **IAM policy (app key): `s3:PutObject` only** on one bucket. No `GetObject`/`ListBucket`/`DeleteObject`
  — a leaked key can only upload, never read/list/delete. Discovery is PhotoKit-native and reconstruction
  metadata rides object headers, so the app never needs `LIST`.
- **Restore is a separate admin-credentialed path** (`s3:ListBucket` + `s3:GetObject`) — never shipped in the app.
- **Networking: Ktor + hand-rolled AWS SigV4** in `commonMain` (okio/KotlinCrypto for HMAC-SHA256).
  Used to **mint presigned PUT URLs** (pure crypto, no network) and optionally `ListObjectsV2`. The
  AWS SDK for Kotlin is JVM/Android-only (no Kotlin/Native), so hand-rolling is the only
  single-codebase path. **The actual upload PUT is done by iOS, not by Ktor.**
- **Build-time config via BuildKonfig**: bucket, region, endpoint, keys → typed Kotlin config from
  Gradle properties; secrets from env/CI/`local.properties` (gitignored), never committed. The
  bucket host also goes into the extension's `BackgroundUploadURLBase`.

---

## 5. UI

- **Compose Multiplatform**, single codebase, rendered on iOS (Skia) and JVM desktop.
- **Design-system abstraction** in `:domain:ui:components` — the **only module that may import
  Material 3**; mechanical rule: **no M3 type may appear in any `App*` signature**. Components are
  **semantic, not customizable primitives** (decided 2026-06-09): parameters carry **data and
  meaning, never appearance** — `text`, `done/total`, closed roles yes; `color`/`textStyle`/`shape`
  never; **no `Modifier` params** (re-opens appearance by the back door) until a call site forces
  one, then layout-only by convention. **Distinct components over role enums** (`PrimaryButton(...)`,
  not `AppButton(role=...)`); buttons named at **emphasis level** (Primary/Secondary = prominence;
  the skin maps to filled/outlined or Cupertino bold/plain), intent-level names (`DestructiveButton`)
  added only when an intent recurs. **Convention-bearing arrangement is skin too**: where platforms
  hold layout opinions (dialog-button order/stacking, grouped lists, title placement), screens use
  **semantic slotted containers** (`ScreenLayout(title) {…}`, `ActionArea(primary=, secondary=)`)
  that own insets/ordering; raw `Column`/`Spacer` only for meaning-free geometry. NOT full per-screen
  templates (every screen change would touch the design system). **No exposed theme tokens** —
  components and containers own all spacing/color/typography internally. Inventory is
  **demand-driven**; single-call-site components are fine (the inventory IS the app's vocabulary).
  The **initial skin is Material 3** (zero dependency risk). A Cupertino skin (the
  [slanos/schott compose-cupertino fork](https://github.com/schott12521/compose-cupertino), or
  hand-rolled) can be added **later without touching screens**. (All Cupertino libs are ~10 months
  stale; the abstraction contains that risk.)
- **v1 screens: minimal** — a single **status screen** (six sync states + last-sync time) whose hero
  is **replaced by an inline permission gate** whenever photo permission ≠ granted. No nav. Backup is
  **always-on once permission is granted**: no enable-backup toggle, no manual "back up now" — the
  gate's CTA is the only button in the app (decided 2026-06-10). Permission is three-state
  (`NOT_DETERMINED` / `DENIED` / `GRANTED`) in `:domain:permission`; **full library access required**
  — iOS `.limited` and `.restricted` map to `DENIED`. ⚠️ **Accepted risk:** on managed/restricted
  devices the Denied gate's "Open Settings" CTA is a dead end; revisit only if such a report appears.
- **State: MVI via Orbit** in `:domain:presentation` (Compose-free). The `:presentation → :ui`
  contract is `StateFlow<UiState>` + actions, so the state model can evolve (MVIKotlin, or Decompose
  navigation — state-lib-agnostic) without touching the UI.
- **Seams are `StateFlow` state holders** (`SyncStatusSource`, `PermissionStatusSource`): the current
  truth is available synchronously, and the container computes its **initial state from real source
  values** — the screen never renders a guess or loading placeholder. Composition-root ordering
  constraint for the engine slice: read the bookkeeping store → construct sources → construct
  container. Command ports (`PermissionRequester.request()/openSettings()`) are fire-and-forget CQS:
  truth only ever arrives via the state ports.
- **Errors: sealed domain errors → `UiState`**, exceptions converted at capability boundaries, logged
  via **Kermit**. Errors-as-reduced-state lets the control panel force any failure state.

### 5.1 Desktop test harness (dual UI)

`:app:desktop` renders **side-by-side**: left = the real `:domain:ui` status screen inside a fixed
**phone-sized frame** (~390×844, visible bezel — preview at ship proportions); right = a **control
panel** (utilitarian raw Material 3, never `App*` — asymmetric investment: the panel is long-lived
test equipment, not product). Both panes bind the same Orbit container. The panel has **two
permanent sections** (decided 2026-06-09):

- **Display overrides** (born in the UI-mock slice, before the engine exists): buttons that forge
  display state for UI iteration, in two groups plus a behavior knob (permission-gate slice):
  **Permission** presets write the permission cell only; **Sync** presets write the sync cell *and
  force permission to Granted* (a preset means "show me this screen", impossible while gated); an
  armed **"next request →"** control decides what the fake `PermissionRequester` resolves. All
  mutations go through one small `PanelController` (no inline mutations in composables). Forever
  outside the scenario system — no command indirection, no tests.
- **Engine console** (mode B; replaces the earlier "world controls" idea — there is **no world
  simulator**, decided 2026-06-11): an event composer (build `ResourceChanged` test resources; push
  `UploadFailed` with a chosen error; auto-responder with configurable delay/failure modes) driving
  a **real `SyncEngine`**, plus a **jobs journal** pane listing every `UploadJob` the engine
  emitted (retry chains visible as `attempt 0 → 1 → …`). The UI is skin over a plain
  `EngineConsole` core (`submit()` / `journal: StateFlow<List<JournalEntry>>` / injectable clock)
  that tests drive directly — and the same API the future `ScenarioStep` indirection wraps
  (scripted scenario runner; assemble from orbit-test/jqwik, evaluate Gherkin before inventing a
  DSL). Display overrides and the console stay **unconnected** until the status-track app slice
  bridges fold → `SyncStatusSource` → phone frame.

---

## 6. Testing strategy

- **Unit (JVM)** — `SyncEngine` decision tests with a fake `UploadRequestProvider` and an
  in-memory `LedgerBackend` (the decision table: skip on proof, hope-never-skips, re-upload on
  version change; retry re-mint chains with `attempt` counting; provider-failure rethrow leaving
  the ledger untouched; suffix-replay convergence — duplicate reports converge instead of
  drifting); `LedgerBackend` contract tests run against BOTH the in-memory backend and the
  SQLDelight backend on a JVM sqlite driver (incl. aggregate reads, the change ding, and
  writer stamping under a fixed clock); `LedgerWatcher` stream tests; the five-row
  classification decision table and `LedgerSyncStatusSource` tests (real watcher + in-memory
  backend + fake permission source); provider-impl tests own the encoding/placement
  contract (slice ③ onward); SigV4 presign; Orbit reducers. The bulk of the logic is here,
  off-device.
- **Integration** — `:capability:s3` against **s3mock** (Testcontainers): mint a presigned PUT, do
  the PUT via Ktor, assert via `LIST`. ⚠️ **Accepted risk:** s3mock does **not validate signatures**,
  so SigV4 is only truly exercised by the on-device extension upload. If a first real upload fails,
  signing is the prime suspect — the quick follow-up is a Garage (SigV4-validating) Testcontainers
  test or a real-AWS smoke test. Not adding that now (per decision).
- **iOS** — the upload extension is **physical-device only** in the current iOS 27 beta (no
  simulator). Plan: **manual on-device testing** now; move the extension into **simulator XCTest/CI
  once Apple adds simulator support** (expected by GA). Gallery/app (non-extension) parts can be
  simulator-tested earlier.
- **Desktop** — container reduction via `orbit-test` + Compose UI tests on the status screen. Test
  only MVP-permanent code: the panel, `PanelController`, and fake wiring are test equipment and get
  no tests (until the ScenarioStep interpreter becomes load-bearing test infra — then it does).
  CI: Compose Desktop UI tests touch AWT and **require a display on Linux — add an Xvfb step**
  (copy JetBrains' own compose-tests workflow incantation, incl. `+extension GLX`). Manual
  exploration via the control panel.

---

## 7. Chosen libraries

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform | single codebase; Material 3 behind a design-system abstraction |
| State | **Orbit MVI** (10.0.0, 2026-05: full KMP + CMP support) | cleanest/most-modern MVI DSL; Compose-free; Decompose-able later; built w/ Kotlin 2.1.21 — fine for JVM, recheck klibs at the iOS slice |
| DI | **Manual composition root** | no deps, compile-safe; Koin if it grows |
| HTTP | **Ktor** (multiplatform) | SigV4 **presign only**; **not** the upload PUT (iOS does that); app never `LIST`s |
| Crypto/IO | **okio** (+ KotlinCrypto if needed) | App-Group file IO + HMAC-SHA256 for SigV4 |
| Engine ledger | **SQLDelight** (2.3.2) | the engine's per-key upload memory; single writer (extension), read-only app connection; JVM sqlite driver for tests, native driver at the iOS slice |
| Persistence | **okio + kotlinx.serialization** | tiny App-Group store: change token, residue, deferred set |
| Config | **BuildKonfig** | typed build-time config; secrets from env/CI |
| Logging | **Kermit** (~1k★, active) | multiplatform |
| iOS integration | **direct framework integration** | `embedAndSignAppleFrameworkForXcode`; framework in app + extension |
| Test S3 | **adobe/s3mock** (Testcontainers) | logic only; doesn't validate SigV4 (accepted risk) |

---

## 8. Open / deferred decisions

**Resolved (deferred-items pass):**
- Object key = `photos/` + encoded filename `<cloudId>-<kind>.<ext>` (flat bucket; per-resource metadata keys, no content-hash/hashing/cache; key identity = `PHCloudIdentifier`, 2026-06-12); `Content-Type` from resource UTI.
- Back up **all `PHAssetResource`s** per photo asset incl. Live Photo paired video → edit-handling dissolves (edits add resources, never overwrite).
- Min iOS **27.0**, `PHBackgroundResourceUploadJobExtension`; discovery via `PHPersistentChangeToken`; job system is source of truth.
- Reconstruction metadata via **signed `x-amz-meta-*` headers** on each resource (resource-level + duplicated asset-level); relationship via `photos/<assetId>/` prefix (cloud identifier); restore = `LIST` prefix. **No sidecar — the app uploads nothing.** Albums skipped (cosmetic). App IAM = `PutObject` only; restore = separate admin path (`ListBucket`+`GetObject`).
- These dissolve the old temp-file, throttling, hashing-cache, iCloud-download-for-hashing, first-sync-cost, edit-handling, periodic-trigger, sidecar, and app-side-BGTask-upload items.

**Resolved (sync-engine architecture pass, 2026-06-11):**
- **Platform-driven decision core** replaces the planned `GalleryService`/`UploadJobService`
  pull-seams: platforms submit `SyncEvent`s, the stateless `SyncEngine` answers with one
  `UploadJob` per event (§2.2). `:capability:gallery`/`uploader`/`store` dissolved; `:capability:s3`
  returns later as one `UploadRequestProvider` impl; no `:domain:model` — all types live in `:domain:sync`
  (renamed `:domain:engine` in the status-core pass, which also split out `:domain:status`).
- **Resources-only sync domain** (2026-06-12): the engine knows only `Resource` — a concrete
  value type (filename, contentType, metadata, `data: Any` — the opaque platform payload:
  `PHAssetResource`, bytes, path; always present; engine and provider never read it; `Any` by
  choice — a generic would infect all six seam types and erase to `id` in the ObjC header). The
  **asset layer** — asset→resource fan-out, filename *layout* (`<cloudId>-<kind>.<ext>`),
  asset-metadata duplication, an `AssetChanged`-style event — is a later shared slice **above**
  this seam, performed platform-side until it exists. The filename is pure identity (plain
  string); **encoding and placement are the provider's** (per-transport: URL path vs header),
  under the deterministic-and-injective filename→destination contract — idempotency lives there.
  Resource kinds in the filename layout remain **open platform strings** (semantic hints
  deferred); restore stays door-open (no format spec yet).
- **Retry forever in v1** (no attempt budget, no GiveUp); every retry re-mints from
  `job.request.resource`, so expired presigned URLs heal. ETag recording dropped (no v1 consumer).
- **Key identity = `PHCloudIdentifier.stringValue`** (2026-06-12): survives restores and device
  migrations — no duplicate trees in the bucket. Unresolvable ids defer the asset (no local-id
  fallback in keys — nondeterministic identity would duplicate objects). One batched
  `cloudIdentifierMappings` call per cycle. Conditional on two on-device verifications (below);
  fallback if they fail: `localIdentifier` keys + re-accept duplicate-tree-on-restore. The sync
  domain is unaffected either way (key identity is asset-layer/platform business).
- ~~Status projection = `StatusEvent`s + reducer~~ (superseded 2026-06-12 — see the
  ledgered-engine pass below).
- Desktop harness gains an **engine console** instead of a world simulator (§5.1); display overrides
  and console stay separate modes.
- Delivery sliced as a **2-track × 3-rung matrix** (engine/status × shared/platform/app), one
  OpenSpec proposal per cell, sequenced: ① engine shared core → ② status shared core → ③ JVM engine
  console + DumbHttpRequestProvider → ④ JVM status feed/source → ⑤ harness mode B UI → ⑥ status↔screen
  bridge. iOS adapter slices follow the same two-part shape later.

**Resolved (ledgered-engine pass, 2026-06-12 — supersedes "stateless engine" and the
StatusEvent/inbox design above):**
- **The engine owns a SQL ledger** (§2.2): `SyncDecision` (`Upload`/`ReUpload`/`Retry` as `Work`,
  `AlreadyUploaded`), skip only on `COMPLETED` + same `Resource.version` (hopes never skip — the
  loss direction is unforgivable, duplicates are absorbed), `UploadCompleted` reported
  write-then-ack. Driven by research: token expiry is routine and full re-enumeration must be
  harmless; Apple prescribes per-key idempotent tracking; exactly-once across file system and job
  system is impossible. The status fold, the event inbox, and a would-be platform discovery
  ledger consolidate into this one store.
- **Sequential `handle()`** (one in flight per engine; all drivers are sequential loops);
  no transactions; rules in plain Kotlin; dumb single-row upserts. A future second writer (e.g.
  app-side foreground acknowledging) is cheap with single-statement WAL writes but must
  re-examine read-decide-write races (`ON CONFLICT` precedence) in its own slice.
- **Single ledger writer per platform** — the extension on iOS; justified by "the app observes
  nothing and has nothing to report", not by lock mechanics. The app reads the DB read-only
  (status) and detects discovery staleness read-only (library token vs ledger token, §2.4).
- **`active` = operational state derived from permission** (replaces event-recency; §2.4) —
  shared logic, no clocks; SUSPENDED becomes display-override-only in v1.
- Accepted-cost swap: stale remote metadata headers (version unchanged ⇒ skip) replaces
  re-upload-all-bytes-on-metadata-change; re-uploads become **visible** in status.
- A middleware decomposition of the engine was explored and deferred (see the sync-engine-ledger
  change's design.md, D10) — revisit when the console journal or an attempt budget demands it.

**Resolved (status-core pass, 2026-06-12 — slice ②, implements the status projection):**
- **Modules renamed and split**: `:domain:sync` → `:domain:engine`; new `:domain:status`
  (seam types + `LedgerSyncStatusSource`, engine/permission deps implementation-scoped);
  presentation dropped its engine dependency — the api() leak of engine types is plugged (§2.1).
- **The ledger signals its own changes**: `LedgerBackend` grew `changes` (ding per put) and
  `aggregates()` (one snapshot-consistent read); third ledger type `LedgerWatcher` streams
  deduped aggregates; `LedgerEntry` grew writer-stamped `updatedAt` (injected clock — engine
  stays clock-free, backends verbatim; duplicate records converge on state/attempt/version, the
  timestamp moves). SQLDelight typed columns, adapters hidden in one factory (§2.2).
- **Classification went suspended-first and FAILED was deleted** (§2.4): `!active` outranks
  everything; the FAILED vertical (state, UI state, hero, preset) is untellable under
  retry-forever + lastFinishedAt-as-newest-completion. Harness finished-presets forge
  `active = true`.
- **Slice ④ (JVM status feed/source) absorbed**: status is shared SQL over the ledger — what
  remains is wiring, owned by slices ③ (harness DB instance) and ⑥ (source↔screen composition).
  Ladder: ② → ③ → ⑤ → ⑥.

**Still open — iOS 27 API verification (on device / Xcode):**
- **TOP RISK:** resumable-upload `OPTIONS` preflight vs raw S3 — does the system fall back to plain `PUT`, or is a tiny edge endpoint needed? (§3.3). Spike first.
- System uploader accepts a **query-string presigned URL** destination + `BackgroundUploadURLBase` host match.
- Required **entitlements**; exact `PHAssetResource` ↔ job ↔ key association; `creationRequestForJob` semantics.
- App Group wiring (config + change token + bookkeeping shared by app and extension; framework in both targets).
- Timing of **simulator support** for the extension (affects when iOS tests move into CI).
- Does the job system expose **change observation** (KVO/delegate) for live job-state updates while
  the app is foreground, or is liveness poll-only (re-`fetchJobs` + `photoLibraryDidChange` + Darwin
  notify)? Affects only the iOS `SyncStatusSource` impl — the snapshot seam absorbs either (§2.3).
- Can `fetchJobs` **enumerate in-flight jobs** (duplicate-job dedup on re-entry, §3.3)? The
  residue bookkeeping works regardless; dedup is belt-and-braces.
- **SQLite WAL cross-process behavior in the App Group**: app read-only connection reading while
  the extension writes (expected safe — single writer, short single-statement transactions — but
  verify on device).
- `creationRequestForJob` with **iCloud-offloaded (non-local) resources**.
- **App Group file-protection class** for ledger-DB and bookkeeping writes from a locked-device
  extension (`NSFileProtectionCompleteUntilFirstUserAuthentication`).
- **Swift ↔ `Flow` interop** for collecting `handle()` from the extension (SKIE or a thin wrapper).
- **Cloud-identifier prerequisites (key identity — verify BEFORE the first real upload):**
  (a) **provisional-window determinism** — is `PHCloudIdentifier.stringValue` for an asset
  identical before/after iCloud reconciliation (freshly created assets, offline, never-signed-in
  devices)? If a provisional value can change, cloud-id keys are disqualified;
  (b) **batch-lookup cost** — does one `cloudIdentifierMappings` call per cycle fit the
  extension's time budget at initial-backup scale (~50k assets)?

**Resolved — other:**
- Limited photo access: v1 **requires full `.authorized`** (the Job extension needs it); `.limited`/`.denied`/`.restricted` → "full access required" + Settings deep link. No limited-subset support.
- Signing validation: **s3mock only** (accepted risk, §6); revisit Garage/real-AWS only if a real upload fails.

**Resolved — ops:**
- CI: push-only `build.yml` shipped with the bootstrap (single JDK 25 Linux runner). Desktop Compose
  UI tests will need the **Xvfb step** (§6). iOS CI once iOS 27 ships and hosted runners carry
  Xcode 27.
- TestFlight: **manual archive + upload from Xcode** while on beta; automate with fastlane (gym+pilot)
  at GA.

**Remaining work is iOS-27-API *verification* (above), not open design decisions.**
