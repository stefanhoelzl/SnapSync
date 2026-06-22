# SnapSync — Design

A **scope pivot** (2026-06-22) of SnapSync, from a *personal one-way library backup* to an
**event-scoped photo contribution** client.

> **What changed and why.** v1 was a personal one-way gallery backup to a single, build-time-fixed
> S3 bucket, with the storage credential embedded in the IPA. This version keeps almost all of that
> machinery — the iOS 27 background upload extension, the ledgered decision engine, the status
> projection, the design-system UI — but repoints it at an **externally-provisioned event**:
>
> - An event is **created outside this app** (a separate tool/backend) and shared as a **QR code**.
> - A device **joins by scanning that QR with the native Camera**, opening a `snapsync://` deeplink
>   that carries the event config (id, name, start date). This **reuses the existing
>   `:capability:config` deeplink-provisioning** path that used to carry `S3Config`.
> - Once joined, the device uploads its **photos taken since the event's start date**, scoped to that
>   event, exactly like v1's always-on backup — but to a **per-event/per-device key namespace**.
> - The storage credential **no longer ships in the app**. An external **edge "mint" endpoint**
>   (bunny.net Edge Scripting) issues short-lived **presigned PUT URLs** keyed by event id; the
>   device holds no storage secret.
> - Collected photos are **viewed elsewhere** (an external tool), never in this app.
>
> **Honest framing.** Because this version is **contribute-only** (no in-app viewing), photos
> **persist indefinitely** (no TTL/purge), and there is **no Leave** yet (deferred), the original
> "temporarily sync photos between connected devices" pitch reduces, at this layer, to: *each device
> uploads its since-start photos to an externally-provisioned shared bucket, viewed externally.* That
> is coherent and buildable; multi-event, Leave, and in-app viewing are explicit later concerns.

---

## 1. Scope

**In scope (this version):**
- iOS app, distributed via **TestFlight** only. **Minimum iOS 27.0.**
- **Event-scoped one-way upload** (contribution): a device joined to an event uploads its local
  photos **with capture date ≥ the event start date** → storage, under that event's key namespace.
  Never downloads, never deletes remotely, never views in-app.
- **One event at a time.** Joining a new event **re-provisions** (replaces) the current one
  (§2.4/§3.2). Multi-event membership is a later concern.
- **Join by scanning an externally-minted QR with the native Camera** → `snapsync://` deeplink →
  event config provisioned via `:capability:config`. The app **does not create events, does not
  display QR codes**.
- **Photo assets, whole library, filtered by capture date** — **all `PHAssetResource`s** of each
  qualifying photo asset (original + edits + adjustments + Live Photo paired video). Standalone
  *video assets* are out of scope.
- **Background uploads via `PHBackgroundResourceUploadJobExtension`** (iOS 27+) — the system
  schedules and **performs** the uploads on the app's behalf, even when suspended/locked. (§3.)
- **Upload destinations are minted on demand** by an external edge endpoint (presigned PUT to
  bunny.net Storage's S3-compatible API). **The device holds no storage credential.**
- A **local desktop (JVM) test app** — phone-frame preview + side-by-side control panel
  (display overrides + engine console; §5.1).

**Explicit non-goals / deferred:**
- **No Leave** in this version (deferred). A joined device contributes until it joins a different
  event. *(This contradicts the opening brief's "users can leave to stop uploading" — consciously
  deferred, not an oversight.)*
- **No in-app viewing / download** — contribute-only. Collected photos are viewed by a **separate
  external tool**.
- **No multi-event membership**, no event creation in-app, no in-app QR generation.
- No Android app yet (architecture keeps the door open).
- No encryption (plaintext upload; the edge endpoint sees no bytes — uploads go device→bucket).
- No bidirectional sync, no remote deletes, no content-dedup.
- No video assets, no album selection, no settings screen.
- **No TTL / purge** — photos persist indefinitely.

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
  ledger queries), and the MVI presentation layer. Knows nothing about the platform or the event.
- **Platform adapters** — own discovery, upload execution, event-config provisioning, and small
  lossy-tolerant bookkeeping; they **drive** the shared decision core with observation events and
  act on the decisions it returns (§2.2). Upload memory lives in the engine's ledger, not the platform.

Implementations are selected by **dependency injection in the app modules** (manual composition
root), *not* `expect`/`actual`. Rationale: the JVM target needs **multiple** impls of the same
seam (in-memory fake for tests, the controllable fake for the desktop app), which `expect`/`actual`
(one impl per compile target) cannot express; a plain `interface` + DI can.

The iOS extension's `processJobs()` **drives** the shared `:domain:engine` decision core with events;
the desktop test app drives that identical core from an engine console (§5.1), so every shared
decision path is exercised off-device. Orchestration (loops, backpressure, job execution) is
deliberately **platform-side** — the shared core decides and remembers.

**The event is platform context, not engine vocabulary.** The engine still knows only *resources*
(§2.2). Event scoping (the `events/<eventId>/<deviceId>/` key placement, the capture-date discovery
filter, the mint-endpoint call) lives **above and beside** the seam, in the platform adapter and the
`UploadRequestProvider` impl — the engine and ledger are event-blind by construction.

### 2.1 Module graph

```
:domain:engine         the shared sync vocabulary + logic; no platform deps:
                         • platform seam (§2.2), resources-only: Resource (concrete value type
                           with opaque platform payload + version), SyncEvent, SyncDecision,
                           UploadJob, UploadRequest, UploadError, UploadRequestProvider.
                           (Asset layer = a slice above.)
                         • SyncEngine — the ledgered decision core (§2.2) + its ledger
                           (LedgerBackend storage seam, LedgerReader/LedgerWriter/LedgerWatcher,
                           SQLDelight)
:domain:status         → :domain:engine + :domain:permission (BOTH implementation-scoped — neither
                         leaks to status's consumers). The status projection (§2.4): SyncStatus +
                         SyncState + SyncStatusSource (snapshot seam, §2.3) and
                         LedgerSyncStatusSource — ledger aggregates × (permission ∧ joined) → snapshots.
:domain:permission     PermissionStatus / PermissionStatusSource / PermissionRequester.
:capability:config     deeplink → EventConfig provisioning (eventId/name/startDate). Was S3Config in
                         v1; now carries the event. Stores into shared Keychain (app + extension).
:domain:presentation   → :domain:status + :domain:permission. Orbit MVI container(s) + UiState.
                         COMPOSE-FREE. NO engine dependency — engine types never reach
                         presentation's compile classpath.
:domain:ui             → :domain:presentation + :domain:ui:components. Compose screens, written
                         exclusively against the App* design system.
:domain:ui:components  semantic App* components + the Material 3 skin — the ONLY module allowed to
                         import Material 3 (§5).

:app:desktop           desktop harness: phone-frame preview + control panel (display overrides +
                       engine console — §5.1). The JVM platform pieces (EngineConsole core, the
                       mint-client fake) live with the platform-integration slices.
:app:ios               wires the iOS adapter + Darwin Ktor engine + framework export → iosApp/

iosApp/                Xcode project (not Gradle): the app target (Swift host + Info.plist, registers
                       the snapsync:// scheme) AND a PHBackgroundResourceUploadJobExtension target
                       (Generic Extension; extension point com.apple.photos.background-upload;
                       BackgroundUploadURLBase = the bunny S3 region host). The :app:ios framework is
                       embedded in BOTH targets (the extension needs the mint client + config). App
                       and extension share an App Group container + a shared Keychain group.
```

Dependency flow: `:domain:engine ← status ← presentation ← ui`; app modules wire the concrete platform
adapters. Every boundary is **compiler-enforced**, and the backend swap is **structural**
(`:app:ios` wires the PhotoKit-backed adapter + the mint-endpoint `UploadRequestProvider`; the
desktop wires the engine console).

The `:capability:s3` hand-rolled **SigV4 presigner is retired** (it ran on-device in v1). With the
device credential-free, **all signing moves to the external edge endpoint**; the on-device
`UploadRequestProvider` becomes a thin **HTTP client to the mint endpoint** (§4). `:capability:gallery`/
`uploader`/`store` remain dissolved (discovery and upload execution are platform-adapter internals).

### 2.2 Platform seam: event → decision core (ledgered)

**The platform drives, the engine decides** (decided 2026-06-11; **ledgered** 2026-06-12). Platform
adapters (iOS extension, desktop engine console, Android later) observe the world — discovered/changed
resources, failed uploads, completed uploads — report each observation to the shared core as an
**event**, and act on the **decision** the core answers with. **Events are observations, never
bookkeeping**: platforms do not filter, dedupe, or track what was uploaded — the engine owns that in
its **ledger**, a SQL-backed per-key store written exclusively by the engine. Rationale (2026-06-12
research): change-token expiry is routine and Apple's remedy is full re-enumeration — without an
uploaded-memory that re-uploads everything; and Apple's own upload-job guidance prescribes
write-then-acknowledge with per-key idempotent tracking (exactly-once across the file system and the
job system is impossible — reports are at-least-once by construction).

**The sync domain transports resources, grouped by an opaque `assetId`** (resources-only decided
2026-06-12; an opaque per-resource `assetId` added 2026-06-22 so the ledger can count/prune by
photo). Since only resources are ever transported, the engine's vocabulary stops there: the
asset→resource fan-out, the **filename layout** (`<localId>-<kind>.<ext>` encodes resource identity
within the device), the **event/device key placement**, and asset-metadata are all **the
platform/provider's** business. The engine carries `assetId` through to the ledger but **never
interprets it** — like `filename` it is pure identity whose meaning is the platform's (iOS: the
asset's `localIdentifier`, normalized). The platform hands each resource a single opaque `filename`
— pure *identity*, a plain string. Its *representation* and *placement* (percent-encoding into a URL
path, the `events/<eventId>/<deviceId>/` prefix, the mint call) are **the provider's
responsibility**, under one contract: the filename→destination mapping must be **deterministic and
injective** — that is where upload idempotency lives.

```kotlin
class Resource(                              // concrete domain type, platform-constructed
    val filename: String,                    // identity; layout + event/device placement is the
    val assetId: String,                     //   platform/provider's (iOS: "<localId>-<kind>.<ext>")
                                             // opaque grouping id (iOS: normalized localIdentifier);
                                             //   engine carries it to the ledger, NEVER interprets
    val contentType: String,                 //
    val version: String,                     // content-identity proof (iOS: asset modificationDate);
                                             //   engine compares EQUALITY ONLY, never parses
    val metadata: Map<String, String>,       // opaque to the engine (carried for the platform's use;
    val data: Any,                           //   NOT uploaded as headers — see §3.1)
)                                            // data: opaque platform payload (PHAssetResource, bytes,
                                             //   path…); engine and provider NEVER read it.

sealed interface SyncEvent {                 // observations, at-least-once, never bookkeeping
    class ResourceChanged(val resource: Resource) : SyncEvent  // a pure QUERY: writes nothing
    class UploadStarted(val job: UploadJob) : SyncEvent   // platform CREATED the job → records REQUESTED
    class UploadFailed(val job: UploadJob, val error: UploadError) : SyncEvent  // → records FAILED
    class UploadCompleted(val job: UploadJob) : SyncEvent  // reported at the ack edge, BEFORE acknowledge()
}

sealed interface SyncDecision {              // the engine's answer: what, if anything, to do
    sealed interface Work : SyncDecision { val job: UploadJob }
    class Upload(override val job: UploadJob) : Work      // not (provably) uploaded yet
    class ReUpload(override val job: UploadJob) : Work    // completed, but version changed
    class Retry(override val job: UploadJob) : Work       // answer to UploadFailed, attempt + 1
    data object AlreadyUploaded : SyncDecision            // ledger proof: nothing to do
}

class UploadRequest(                         // complete, executable: PUT resource → url
    val url: String,                         // a presigned PUT URL minted by the edge endpoint
    val headers: Map<String, String>,        // exactly these headers (Content-Type; no x-amz-meta-*)
    val resource: Resource,                  // rides whole for the failure round-trip
)
class UploadJob(val request: UploadRequest, val attempt: Int)
                                             // attempt: 0 = create platform job, >0 = retry

interface UploadRequestProvider {            // impl: the mint-endpoint HTTP client (test: dumb fake)
    suspend fun provide(resource: Resource): UploadRequest
    // builds the key (events/<eventId>/<deviceId>/<encoded filename>) from event config + device id,
    // calls the external MINT ENDPOINT (event id + key) → presigned PUT URL, returns the full request.
    // CONTRACT: filename → destination is deterministic and injective; Content-Type set; called only
    // for Work answers — never on a skip.
}

interface LedgerBackend {                    // storage seam: dumb row store, last write wins
    val changes: Flow<Unit>                  // ding after every put; "re-read the truth" — where
                                             //   another process writes, feeding this is that
                                             //   backend's concern (iOS: Darwin observer)
    suspend fun get(key: String): LedgerEntry?  // LedgerEntry carries an opaque assetId column
    suspend fun put(entry: LedgerEntry)      // single-row upsert = the unit of atomicity
    suspend fun aggregates(): LedgerAggregates  // one round-trip, counted by PHOTO (assetId): a
                                             //   photo is complete only when all its rows are
    suspend fun clear()                      // wipe all rows (re-provision on join — §3.2)
    suspend fun deleteByAssetId(assetId: String)   // prune one asset's rows (incremental deletion)
    suspend fun retainAssets(keep: Set<String>)    // prune assets ∉ keep (full-enum reconcile);
                                             //   both still dumb: assetId is a 2nd opaque field
}
open class LedgerReader(backend)             // entry(key) — the per-key read-only face (engine's)
class LedgerWriter(backend, clock = System) : LedgerReader
                                             // recordRequested / recordCompleted / recordFailed
                                             //   (each takes assetId); deleteByAssetId / retainAssets;
                                             // stamps updatedAt — the SINGLE stamping point
                                             // (engine clock-free, backends store verbatim).
                                             // ONE writer per platform, by construction: only the
                                             // engine-hosting composition root constructs it
class LedgerWatcher(backend)                 // aggregates: Flow<LedgerAggregates> — cold: current
                                             //   truth on collect, re-query per conflated ding,
                                             //   deduped; the ONLY type surfacing aggregates/dings
class LedgerEntry(key, assetId, state /* REQUESTED|COMPLETED|FAILED */, attempt, version, updatedAt)
class LedgerAggregates(pending, completed, newestCompletionAt /* by PHOTO; null = no photo done */)
                                             // schema: key PRIMARY KEY, assetId (+index), state,
                                             // attempt, version, updatedAt (epoch millis; SQLDelight
                                             // typed columns, adapters hidden in one factory)

class SyncEngine(provider: UploadRequestProvider, ledger: LedgerWriter) {
    suspend fun handle(event: SyncEvent): SyncDecision    // ResourceChanged = pure query (no write)
    // ResourceChanged(r):  ledger absent/FAILED                  → Upload(mint, attempt = 0)
    //                      COMPLETED/REQUESTED + version == r     → AlreadyUploaded (in flight/done)
    //                      COMPLETED/REQUESTED + version != r     → ReUpload(mint, attempt = 0)
    // UploadStarted(j):    → records REQUESTED (write-after-act), answers AlreadyUploaded
    // UploadFailed(j, e):  → Retry(mint, j.attempt + 1) — forever; records FAILED
    // UploadCompleted(j):  → records COMPLETED, answers AlreadyUploaded
}

sealed interface UploadError {               // platform maps raw errors in; policy ignores, logs only
    object Network : UploadError
    class Http(val status: Int) : UploadError
    object Cancelled : UploadError
    class Unknown(val detail: String) : UploadError
}
```

**Engine behavior** (ledger-authoritative, write-after-act; unchanged from v1). `ResourceChanged` is a
**pure query** — it reads the ledger and mints a request for `Work` answers but **writes nothing**. A
key is skipped when the ledger holds it `COMPLETED` **or** `REQUESTED` at the same `version`:
`REQUESTED` means **a job is in flight**, so re-deriving the change feed is idempotent. This is sound
only because `REQUESTED` is recorded **after** the platform creates the job: the three lifecycle
events — `UploadStarted`→`REQUESTED`, `UploadFailed`→`FAILED`, `UploadCompleted`→`COMPLETED` — are the
**only** ledger writers, each an unconditional idempotent upsert. A crash between create and
`UploadStarted` leaves no `REQUESTED`, re-issued later as a **bounded, idempotent duplicate** (one
extra upload) rather than a stranded photo. **Retry forever** — no attempt budget; every retry
re-mints, so expired presigned URLs heal (the re-mint now re-calls the **edge endpoint** rather than
re-signing locally). Provider failures (incl. a mint-endpoint error) **rethrow** from `handle()` with
the **ledger untouched**; the event counts as unprocessed and re-handling is safe. **Sequential
contract:** at most one `handle()` in flight per engine.

**Platform contract.** Act on decisions: `Work` → execute the job, **then report `UploadStarted(job)`**
(`attempt == 0` → create a platform job; `> 0` → retry the existing one *or* acknowledge-and-recreate);
`AlreadyUploaded` → continue. On `limitExceeded` the platform stops creating jobs for the cycle, **does
not advance its discovery cursor**, and returns a *processing* result so it is re-invoked. **Report
completions at the acknowledge edge, BEFORE acknowledging** (`UploadCompleted(job)` → then
`acknowledge()`). Failures are reported as `UploadFailed(job, error)` and answered with `Retry`. **Every
presented job is acknowledged** (iOS errors 50008 otherwise). **Retention is the ledger itself** — a
returned system job is mapped back to its key from its **destination URL** (the last path segment),
since `resource` is **nil for succeeded jobs**; version/attempt come from the ledger row. **One ledger
writer per platform:** the engine (and its `LedgerWriter`) is hosted where uploads are decided — on
iOS, the extension; the app holds a read-only ledger view. Scope filtering (photos yes, standalone
video no; **capture date ≥ event start**) sits above the seam — the engine is media-type- and
event-blind by construction.

**Accepted costs** (eyes open): a crash in the write-after-act window yields **one bounded, idempotent
duplicate** upload on the next re-derivation — never a stranded photo. Retry-forever churns a job slot
on a permanently-broken resource (now also covers a permanently-failing mint endpoint). The
system-surfaces-all-results assumption means a silently-dropped job would leave a `REQUESTED` row
unrescued until a full re-enumeration; deferred until observed on device.

### 2.3 Sync → presentation seam: state snapshots, not events

`:domain:status` exposes progress to presentation as a **snapshot contract** — `SyncStatus` (lifetime
counts, §2.4) observed via `SyncStatusSource { val status: StateFlow<SyncStatus> }` — **not** an event
stream. Why (decided 2026-06-09):

- **The iOS process topology forbids events.** Uploads run in the extension while the app is suspended
  or dead; the app learns what happened by reading the App Group + job system. The UI is inherently a
  projection of persisted state.
- **The fold lives with the engine.** state = fold(events); an event seam would duplicate that fold
  into presentation with drift risk.
- **Snapshots are self-healing** (every emission is the whole truth): no late-subscriber problem, no
  missed-event corruption, safe conflation (`StateFlow`), and initial render is the same code path as
  any update.
- Platform signals (`photoLibraryDidChange`, Darwin notifications, foreground entry, polling) and
  **event-config changes (join)** are **invalidation dings handled inside the iOS impl** — each
  triggers re-read + a fresh emission; none leaks into the contract.
- One-shot effects (toasts, later) are derived **downstream** by diffing consecutive snapshots in the
  Orbit container.

### 2.4 Status projection: read-only queries over the engine's ledger

How `SyncStatusSource` (§2.3) gets its truth. The UI seam stays a level-triggered
`StateFlow<SyncStatus>`; behind it, `LedgerSyncStatusSource` (in `:domain:status`) combines the
ledger's `LedgerWatcher` stream with the permission seam **and the event-config (joined) state** and
mints snapshots — constructed via a suspend factory that reads the current truth first, so the seam's
synchronous-first-value promise holds. Writes ding, the watcher re-queries, the source re-mints.

- **Counts are lifetime aggregates by PHOTO (assetId), not resource row** (added 2026-06-22):
  `pending` = photos with any non-`COMPLETED` resource, `completed` = photos whose resources are
  all `COMPLETED`; `lastFinishedAt` = the newest fully-completed photo's `updatedAt`. (The state
  classification is unaffected — "any resource pending" ⟺ "any photo pending".) `ReUpload` flips a
  row back to `REQUESTED`, so re-uploads are visible. `failed` ≡ 0 from the real source
  (retry-forever) and `estimatedRemaining` ≡ null (never estimates) — both fields exist for
  classification and fakes.
- **Classification is suspended-first**: `!active → SUSPENDED; pending > 0 → IN_PROGRESS;
  lastFinishedAt == null → NEVER_SYNCED; failed > 0 → INCOMPLETE; else COMPLETE`. **There is no FAILED
  state** (untellable under retry-forever).
- **`active` = operational state** (not a liveness heuristic): *contribution machinery is allowed to
  run* — `permission == GRANTED` **AND a valid event config is present (joined)**, derived once inside
  `LedgerSyncStatusSource`. Shared logic, no clocks. Consequence: **the setup gate covers the hero
  whenever `active` is false** — i.e. when permission isn't granted **or** when no event is joined
  (§5). "Not joined" is therefore **the gate's no-event state**, exactly mirroring v1's no-storage-setup
  gate; it is not a new top-level `SyncState`.
- **Cross-process topology (iOS):** the extension hosts the engine and the single `LedgerWriter` (WAL,
  short single-statement writes); the app opens the database **read-only**. The app-side backend feeds
  its `changes` flow from the Darwin notification the extension posts.
- **Staleness detection is read-only:** the app compares the photo library's `currentChangeToken` with
  the platform bookkeeping's last token — mismatch ⇒ undiscovered work ⇒ status can say "waiting for
  system" instead of a false COMPLETE (later slice).
- **Joining re-provisions** (§3.2): on a new event deeplink, the platform clears the ledger and the
  discovery cursor and sets the new start-date baseline, so status resets to never-synced for the new
  event. Uninstall wipes the App Group → same reset. Re-sync is idempotent.

---

## 3. Sync design

### 3.1 Object keys (event/device-scoped, metadata in the path)

The upload-job API fixes the **destination URL at job-creation time**, but the **system** reads the
bytes during upload — so we never see the bytes and **content-hash keys are impractical**. v1 used
per-resource metadata keys; this version namespaces them under the event and the contributing device.

We upload **every `PHAssetResource` of each qualifying photo asset** (complete fidelity): `.photo`
(original, immutable), `.fullSizePhoto` (edited render, if any), `.adjustmentData` (edit instructions),
alternates, **and Live Photo `.pairedVideo`/`.fullSizePairedVideo`**. "Qualifying" = the asset's
**capture date ≥ the event start date** (§3.2).

- **Key: `events/<eventId>/<deviceId>/<encoded filename>`** where the filename is
  **`<localId>-<kind>.<ext>`**, composed platform-side:
  - **`<eventId>`** — from the joined event config (deeplink, §4). **High-entropy/unguessable** — it
    doubles as the upload capability, since the mint endpoint authorizes by event id alone (no token).
  - **`<deviceId>`** — a random UUID generated at first launch and persisted in the App Group, stable
    across events; identifies the **contributing device** (the only attribution; no display name).
  - **`<localId>`** = the asset's `PHAsset.localIdentifier` (**no `PHCloudIdentifier` resolution** —
    dropped with its batch-lookup cost and provisional-window risk; matches the device-verified impl).
    Per-device namespacing makes the local id sufficient — no cross-device dedup is attempted.
  - **`<kind>`** = the open platform resource-kind string (e.g. `ios.photo`, `ios.fullSizePhoto`);
    `Content-Type` from the resource's UTI.
  - The filename is pure *identity*; the **provider** owns its representation — percent-encoding (bytes
    outside `[A-Za-z0-9._-]` → `%XX`) and the `events/<eventId>/<deviceId>/` placement — under the
    deterministic-and-injective contract (§2.2). The bucket is **flat within that prefix**.
- **Edit-handling dissolves:** the original resource is immutable; an edit just **adds**
  `.fullSizePhoto` + `.adjustmentData` resources, which surface via the change feed as *new* jobs under
  *new* keys. Nothing is overwritten.
- **No `x-amz-meta-*` reconstruction headers.** bunny Storage's S3-compatible API **does not support
  custom metadata headers** (§4), so v1's signed-header reconstruction scheme is **removed**. The
  downstream (external) viewer recovers what it needs from **(a)** the key path (event, device,
  resource kind, original identity) and **(b)** the **EXIF/maker metadata already inside the image
  bytes** (capture date, geolocation, camera). Sign only `host` + `content-type`.
- Trade-offs (accepted): **no content dedup**; per-device key namespaces mean the *same* physical photo
  shared by two devices lands twice (once per device) — acceptable and arguably desirable for
  attribution. Asset-level facts not present in EXIF (favorite flag, album membership) are **not
  preserved** (cosmetic; deferred).

### 3.2 Discovery & state (date-filtered PhotoKit discovery, engine-owned memory)

- **Discovery** is the `PHPersistentChangeToken`, **filtered to capture date ≥ event start**:
  - **Initial join:** `PHAsset.fetchAssets` over the library with a `creationDate >= startDate`
    predicate + capture `currentChangeToken` as the baseline cursor.
  - **Steady state:** `fetchPersistentChanges(since:)` yields change **records**, each carrying its own
    serializable token (durable cursor advances **per record**); newly-relevant assets are filtered by
    `creationDate >= startDate` before fan-out. Assets captured **before** the start date are ignored,
    even if added/changed later.
  - **Token expiry is routine** (`persistentChangeTokenExpired`) — the remedy is full (date-filtered)
    re-enumeration, harmless because the ledger answers `AlreadyUploaded` for everything already done.
- **Joining re-provisions** (the device-verified path): on a new event deeplink, **clear the ledger**
  (`LedgerBackend.clear()`) + the discovery cursor, persist the new `{eventId, startDate}`, and
  **re-register the extension**, so the library re-uploads from scratch into the new event's namespace.
  (No Leave: there is no deprovision-to-idle path in this version; switching events is the only
  transition.) Re-upload begins on the OS's next extension invocation — a library change reliably
  triggers one.
- **State**: upload memory is the **engine's ledger** (single writer = the extension). The platform
  keeps only small, **lossy-tolerant** discovery bookkeeping in the App Group: `{lastToken,
  startDate, eventId, deviceId, deferredIds?}`. Losing the residue costs one record's worth of
  duplicate jobs, absorbed downstream. The job system remains the execution authority
  (`acknowledge()` frees `jobLimit` slots). The app never `LIST`s the bucket (mint = `PutObject`-only
  presigned URLs); restore/view-side `LIST` is a separate external admin path (§4).

### 3.3 Flow

> Phases below carry over the device-verified v1 model (§2.2), with two deltas: discovery is
> **date-filtered to the event start**, and the destination URL is **minted by the external edge
> endpoint** (per resource, via the `UploadRequestProvider`) instead of signed on-device.

**App (foreground):** request `.readWrite` photo authorization → (on a valid joined event config)
`setUploadJobExtensionEnabled(true)` → show status (job states + App-Group progress). **The app
uploads nothing itself.** A new event deeplink re-provisions (§3.2). There is **no enable/disable
toggle and no Leave** — contribution is on whenever permission is granted *and* an event is joined.

**Extension — `processJobs()` (system-invoked, hosts the shared `SyncEngine` + `LedgerWriter`):**
the system downloads each resource (incl. from iCloud) and performs `job.destination` with the
**resource bytes as the request body**. We manage the queue in three phases:
1. **Adjudicate failures** — `fetchJobs(action: .retry)`; produce the `UploadJob` (key from the
   destination URL; version/attempt from the ledger), map the error → `UploadError`, report
   `UploadFailed(job, error)`; the engine answers `Retry` (`attempt + 1`). The `Retry`'s fresh request
   is minted by **re-calling the edge endpoint** (heals an expired presigned URL). ⚠️ **One retry per
   system job only**: first failure → `retry(destination: fresh URL)`; already-retried → `acknowledge()`
   + re-create a fresh system job.
2. **Acknowledge completed** — `fetchJobs(action: .acknowledge)`; report `UploadCompleted(job)` (engine
   records `COMPLETED` — write-then-act), **then** `acknowledge()`. A crash between the two re-presents
   the job → a duplicate report, absorbed by the idempotent ledger.
3. **Create new jobs** — discovery (§3.2, date-filtered). For each qualifying asset, expand to its
   `PHAssetResource`s and wrap each as a `Resource` — filename `"<localId>-<kind>.<ext>"`, `version` =
   the asset's `modificationDate`. Report `ResourceChanged` per resource and act on the decision:
   `AlreadyUploaded` → continue (no job slot); `Work` → `provider.provide(resource)` **calls the mint
   endpoint** (eventId + key) for a presigned PUT URL, then take the `PHAssetResource` from
   `decision.job.request.resource.data`, build the `URLRequest` (PUT + `Content-Type`) →
   `creationRequestForJob(destination:resource:)` in `performChanges {}`. On `limitExceeded` stop
   reporting for this cycle, do **not** advance the cursor, and return `.processing`.
4. Return `.completed` / `.processing` (incl. while the ledger still has pending rows, to flush
   completions) / `.failure`. `willTerminate()` cancels the in-flight collection.

**Presigned-URL expiry:** the system may run a job much later. Mint with a **long expiry** (bunny S3
allows up to **7 days**), and the **retry path re-mints** a fresh URL. (Header-based SigV4 would be
~15 min — unsuitable.)

> ⚠️ **TOP RISK — UNSIGNED-PAYLOAD on bunny S3 presigned PUT.** bunny's S3-compatible docs do **not**
> state that `UNSIGNED-PAYLOAD` (a body-less signed payload hash) is supported. We rely on it: the
> extension **never sees the bytes**, so it cannot compute a content hash to bake into the signature.
> If bunny demands a real payload hash, presigned PUT is unusable with the background extension (the
> exact reason bunny's *native* presigning — which requires a SHA256 checksum — was rejected). **Spike
> this first** against the S3-compatible endpoint before committing.
>
> ⚠️ **Also verify on device / in Xcode** (iOS 27 API): the system accepts a **query-string presigned
> URL** destination under `BackgroundUploadURLBase` = the bunny S3 region host; whether the system
> sends an **`OPTIONS` resumable-upload preflight** to a bunny endpoint (v1 verified raw S3 PUT needs
> none — re-verify for bunny); required **entitlements**; `PHAssetResource ↔ job ↔ key` association;
> `creationRequestForJob` semantics; and **mint-endpoint reachability/latency from the extension**.

### 3.4 Why this shape

iOS 27's `PHBackgroundResourceUploadJobExtension` lets the **OS schedule and perform** uploads
(power/network-aware, across suspension/lock) — dissolving temp-file handling, throttling, manual
`URLSession`, and the periodic-trigger problem. We contribute only: **which assets** (date-filtered
change feed), **where** (a presigned destination URL **minted by the external edge endpoint**), and
**retry/acknowledge** policy. The extension links the shared framework and reads the joined event
config from the shared Keychain + bookkeeping from the App Group.

### 3.5 Downstream reconstruction (external, out of scope)

There is **no sidecar and no app-side upload** — and now **no `x-amz-meta-*` headers** either (§3.1,
unsupported by bunny S3). The collected photos are made sense of **externally**:

- The **key path** carries event id, device id, resource kind, and the resource's original identity.
- The **image bytes carry their own EXIF/maker metadata** (capture date, geolocation, camera, etc.).
- An external tool (admin-credentialed, `ListBucket` + `GetObject`) can `LIST` `events/<eventId>/` to
  enumerate all contributions, group by `<deviceId>`, and read EXIF per object.

The **event-creation tool**, the **QR minting**, and any **viewing/restore tool** are all **external,
out of scope** for this app. This doc specifies only the contracts the app depends on (§4).

---

## 4. Storage, auth & config

The device holds **no storage credential** (the v1 embedded IAM key is gone). Authorization to upload
is mediated by an external **edge mint endpoint**.

- **Storage: bunny.net Storage via its S3-compatible API** ([docs](https://docs.bunny.net/storage/s3))
  — SigV4, presigned URLs, path-style endpoint `https://<region>-s3.storage.bunnycdn.com`, expiry
  ≤ 7 days. **Closed preview** as of mid-2026 (access required). Known limits we design around: **no
  `x-amz-meta-*`** (§3.1), no versioning/ACL/SSE/tagging/lifecycle, no batch delete.
- **Mint endpoint (external, bunny.net Edge Scripting / Deno):** the app's only backend dependency.
  - **Request:** event id + the desired object key (`events/<eventId>/<deviceId>/<encoded filename>`)
    + `Content-Type`.
  - **Response:** a **presigned PUT URL** for that key (signed server-side with the bunny S3 storage-zone
    credential, which lives only on the edge).
  - **Authorization: by event id only** (no token — the QR carries no secret beyond the id). The event
    id is high-entropy, so possession of it is the capability. **The edge MUST constrain the minted key
    to the `events/<eventId>/` prefix** (and should reject overwrites of existing objects) so a holder
    can't write outside the event or clobber others.
  - **Base URL is compile-time-baked** (BuildKonfig) — a fixed host, not carried in the QR.
- **Event creation + QR minting (external, out of scope):** a separate tool creates an event
  (high-entropy id, name, **start date = creation time**) and emits a `snapsync://` QR. Not implemented
  by this app; documented here only as the source of the deeplink payload.
- **Deeplink payload (`:capability:config`):** the QR encodes a **`snapsync://` custom-scheme** URL
  (kept from the v1 deeplink-config slice; accepted: native-Camera handling of custom schemes is less
  reliable than a Universal Link and **dead-ends if the app isn't installed**) carrying
  **`{ eventId, name, startDate }`**. Scanning with the native Camera opens the app, which provisions
  the event into the shared Keychain and re-provisions sync (§3.2). `name` is carried for possible
  future display; the status screen shows **progress only** (§5).
- **Networking: Ktor (Darwin engine)** for the mint-endpoint HTTP call (from both app and extension).
  The **upload PUT is performed by iOS**, not by Ktor. The **hand-rolled SigV4 presigner is retired**
  on-device (signing is the edge's job).
- **Build-time config via BuildKonfig**: the **bunny S3 region host** (→ `BackgroundUploadURLBase`) and
  the **mint-endpoint base URL**. **No secrets** are baked (the whole point of the mint endpoint). The
  event id/name/start arrive at **runtime via the deeplink**, not at build time.

---

## 5. UI

- **Compose Multiplatform**, single codebase, rendered on iOS (Skia) and JVM desktop.
- **Design-system abstraction** in `:domain:ui:components` — the **only module that may import
  Material 3**; **no M3 type may appear in any `App*` signature**. Components are **semantic, not
  customizable** (params carry data/meaning, never appearance; no `Modifier`/color/shape/textStyle
  params). **Distinct components over role enums**; buttons named at **emphasis level**;
  convention-bearing arrangement via **semantic slotted containers** (`ScreenLayout(title){…}`,
  `ActionArea(primary=,secondary=)`). No exposed theme tokens. Inventory is **demand-driven**. The
  **initial skin is Material 3**; a Cupertino skin can be added later without touching screens.
- **Screens: minimal — a single status screen** (the v1 sync states + last-sync time) whose hero is
  **replaced by an inline setup gate** whenever `active` is false (§2.4). The gate has two
  not-ready states, mirroring v1:
  - **Permission not granted** → "full access required" + Settings deep link (full library access
    required; `.limited`/`.restricted` map to `DENIED`).
  - **Not joined (no event config)** → a prompt to **join by scanning the event's QR with the
    Camera**. *(The app shows no scanner of its own — joining is native-Camera → `snapsync://`
    deeplink. The gate explains this.)*
  Once permission is granted **and** an event is joined, the hero is the **status screen, progress
  only** — no event name, no Leave, no enable toggle, no manual "sync now". (Leave + event identity are
  deferred.)
- **State: MVI via Orbit** in `:domain:presentation` (Compose-free). The `:presentation → :ui` contract
  is `StateFlow<UiState>` + actions.
- **Seams are `StateFlow` state holders** (`SyncStatusSource`, `PermissionStatusSource`): current truth
  is available synchronously; the container computes its **initial state from real source values**.
  Command ports (`PermissionRequester.request()/openSettings()`) are fire-and-forget CQS. Event-config
  provisioning (the deeplink handler) writes config + triggers re-provision; the status source picks up
  the change via its ding.
- **Errors: sealed domain errors → `UiState`**, converted at capability boundaries, logged via
  **Kermit**.

### 5.1 Desktop test harness (dual UI)

`:app:desktop` renders **side-by-side**: left = the real `:domain:ui` status screen inside a fixed
**phone-sized frame** (~390×844); right = a **control panel** (utilitarian raw Material 3, never
`App*`). Both panes bind the same Orbit container. Two permanent sections:

- **Display overrides:** buttons that forge display state for UI iteration — **Permission** presets
  (write the permission cell), **Joined/Not-joined** presets (forge the event-config gate state), and
  **Sync** presets (write the sync cell and force permission Granted + joined). An armed
  "next request →" control decides what the fake `PermissionRequester` resolves. All mutations go
  through one `PanelController`.
- **Engine console:** an event composer (build `ResourceChanged` test resources; push `UploadFailed`
  with a chosen error; auto-responder with configurable delay/failure modes) driving a **real
  `SyncEngine`**, plus a **jobs journal** pane listing every `UploadJob` (retry chains visible). The UI
  is skin over a plain `EngineConsole` core that tests drive directly. The mint-endpoint provider is
  faked here (a dumb URL builder) — no live edge calls in the harness.

---

## 6. Testing strategy

**Coverage principles.** Three standing rules:
1. **Every unit test runs on the iOS simulator too.** Logic tests live in `commonTest` so they execute
   on **both** JVM and `iosSimulatorArm64`. Per-platform test source sets hold **only** driver/cinterop
   wiring, exercised through a shared contract.
2. **`:app:ios` and the `iosApp/` Swift host are a thin, untestable wiring layer.** All logic — shared
   *or* iOS-specific — lives in `domain`/`capability` modules under test.
3. **Platform-seam ↔ UI-state integration tests** assemble the real `engine → status → presentation`
   stack and assert `UiState` from injected `SyncEvent`s, faking only the execution edge (in-memory
   `LedgerBackend`, fake `UploadRequestProvider`). They live in the test-only **`:test:integration`**
   module (`commonTest`).

- **Unit (JVM + simulator)** — `SyncEngine` decision tests (skip on proof, hope-never-skips, re-upload
  on version change, retry re-mint chains, provider-failure rethrow, suffix-replay convergence);
  `LedgerBackend` contract tests against the in-memory and SQLDelight (JVM sqlite) backends incl.
  **`clear()`** for re-provision; `LedgerWatcher` stream tests; the classification decision table and
  `LedgerSyncStatusSource` tests with **`active` = permission ∧ joined** (real watcher + in-memory
  backend + fake permission + fake event-config source); **`UploadRequestProvider` (mint client) tests**
  — key construction (`events/<eventId>/<deviceId>/<encoded filename>`, percent-encoding,
  deterministic+injective), request shaping, mint-endpoint error → rethrow; **date-filter discovery
  predicate tests** (capture ≥ start) where the logic is shared; `:capability:config` deeplink-parse
  tests (`snapsync://` → EventConfig); Orbit reducers.
- **Mint client** is exercised against a **fake HTTP endpoint** (Ktor MockEngine) in commonTest — the
  real edge endpoint is external. There is **no on-device SigV4 to golden-test** anymore (the v1 SigV4
  golden suite retires with the presigner).
- **iOS** — the upload extension is **physical-device only** in the current iOS 27 beta. Plan: **manual
  on-device testing** now; move into simulator XCTest/CI once Apple adds simulator support. The mint
  call, deeplink provisioning, and date-filter discovery can be simulator/JVM-tested earlier.
- **Desktop** — container reduction via `orbit-test` + Compose UI tests on the status screen and the
  two gate states. Panel/`PanelController`/fakes are test equipment (no tests). CI: Compose Desktop UI
  tests render offscreen under `-Djava.awt.headless=true` — **no display / Xvfb needed**.

The ultimate signature/preflight check is the **on-device extension upload** against the bunny S3
endpoint (the UNSIGNED-PAYLOAD + OPTIONS spike, §3.3); if a first real upload fails, the mint signature
or the preflight contract is the prime suspect.

---

## 7. Chosen libraries

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform | single codebase; Material 3 behind a design-system abstraction |
| State | **Orbit MVI** (10.0.0) | Compose-free; Decompose-able later |
| DI | **Manual composition root** | no deps, compile-safe; Koin if it grows |
| HTTP | **Ktor** (Darwin engine) | now **load-bearing**: the `UploadRequestProvider` calls the **mint endpoint** from app + extension. The upload PUT is iOS's; app never `LIST`s |
| Crypto/IO | **okio** (App-Group IO) | hand-rolled SigV4 **retired** (signing moved to the edge); KotlinCrypto no longer needed on-device |
| Engine ledger | **SQLDelight** (2.3.2) | per-key upload memory; single writer (extension), read-only app connection; `clear()` on re-provision |
| Persistence | **okio + kotlinx.serialization** | tiny App-Group store: change token, start date, eventId, deviceId; event config in shared **Keychain** (via `:capability:config`) |
| Config | **BuildKonfig** | bunny S3 region host + mint-endpoint base URL; **no secrets**. Event config is runtime (deeplink) |
| Deeplink | **`:capability:config`** | `snapsync://` → EventConfig provisioning (was S3Config) |
| Logging | **Kermit** | multiplatform |
| iOS integration | **direct framework integration** | `embedAndSignAppleFrameworkForXcode`; framework in app + extension |

---

## 8. Open / deferred decisions

**Resolved (the 2026-06-22 scope pivot):**
- **Contributor-only, event-scoped.** App joins an externally-created event by scanning a `snapsync://`
  QR with the native Camera; uploads photos with **capture date ≥ event start** to
  `events/<eventId>/<deviceId>/<localId>-<kind>.<ext>`. No in-app create/QR/viewing/Leave.
- **Single event at a time**; **join re-provisions** (clear ledger + cursor, new start baseline,
  re-register extension). Multi-event and Leave **deferred**.
- **Storage = bunny.net S3-compatible API**; **device holds no credential**; an external **edge mint
  endpoint** issues presigned PUT URLs **by event id only** (event id = the capability; edge constrains
  keys to the event prefix). Mint base + bunny S3 host are BuildKonfig; **no baked secrets**.
- **`localIdentifier` keys** (no `PHCloudIdentifier` resolution); **device-id attribution**; **no
  `x-amz-meta-*` headers** (unsupported by bunny S3) — downstream reconstruction uses the key path +
  in-file EXIF; v1's §3.5 signed-header scheme **removed**.
- **Full fidelity retained** (all `PHAssetResource`s, incl. Live Photo paired video).
- **`active` = permission ∧ joined**; "not joined" is the **setup-gate no-event state**, not a new
  `SyncState`. Status screen shows **progress only**.
- **`:capability:s3` SigV4 presigner retires**; **Ktor becomes load-bearing** (mint calls); the SigV4
  golden suite retires with it.

**Carried forward unchanged (architecture):** the ledgered `SyncEngine` + platform seam (§2.2),
snapshot status seam (§2.3), suspended-first classification with no FAILED state (§2.4), the
design-system rules (§5), the dual-UI desktop harness (§5.1), and the three testing rules (§6).

**Still open — verify on device / Xcode (iOS 27 + bunny S3):**
- **TOP RISK:** does a **bunny S3 presigned PUT accept `UNSIGNED-PAYLOAD`** (no body hash)? If not, the
  background-extension upload model is incompatible — spike first (§3.3).
- Does the system send an **`OPTIONS` resumable-upload preflight** to bunny, and does it fall back to
  plain `PUT`? (v1 verified no preflight against raw AWS S3; re-verify for bunny.)
- System uploader accepts a **query-string presigned URL** destination + `BackgroundUploadURLBase` =
  bunny S3 region host.
- **Mint-endpoint reachability + latency from the extension** (one call per `Work` resource; consider
  batching if chatty).
- **bunny S3 closed-preview access**; confirm region, path-style signing, and 7-day expiry behavior.
- Required **entitlements**; exact `PHAssetResource ↔ job ↔ key` association; `creationRequestForJob`
  semantics (incl. iCloud-offloaded resources).
- **App Group + shared Keychain** wiring (event config + change token + bookkeeping + ledger DB shared
  by app and extension; framework in both targets); App-Group **file-protection class** for
  locked-device extension writes.
- **SQLite WAL cross-process** behavior (app read-only while extension writes).
- **Swift ↔ `Flow` interop** for collecting `handle()` from the extension (SKIE or a thin wrapper).
- **Native-Camera + custom-scheme** reliability (and the not-installed dead-end) — accepted, revisit if
  it bites; a **Universal Link** is the upgrade path.

**Remaining non-trivial design questions (deferred, not blocking):**
- **Leave / deprovision-to-idle** (the opening brief's "stop uploading" — intentionally deferred).
- **Multi-event** membership (fan-out a photo to N events; the engine/ledger become event-multiplexed).
- **In-app viewing** (would reintroduce a read/download path the current design excludes).
- **Backend hardening** (rate-limiting the open create/mint, abuse protection — App Attest is the
  middle-ground option if needed).
