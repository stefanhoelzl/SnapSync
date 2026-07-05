# SnapSync — Design

A **scope pivot** (2026-06-22) of SnapSync, from a *personal one-way library backup* to an
**event-scoped photo contribution** client.

> **What changed and why.** v1 was a personal one-way gallery backup to a single, build-time-fixed
> S3 bucket, with the storage credential embedded in the IPA. This version keeps almost all of that
> machinery — the iOS 27 background upload extension, the ledgered decision engine, the status
> projection, the design-system UI — but repoints it at an **externally-provisioned event**:
>
> - An event is **created in the app** (enter a name → the backend mints it via `POST /events`, and the
>   creating device auto-joins) or externally by a backend tool. Either way it is shared as a **QR
>   code** for others to join (QR generation/sharing is a separate concern, not in this app yet).
> - A device **joins by scanning that QR with the native Camera**, opening a `snapsync://` deeplink
>   that carries the event config (id, name, start date). This **reuses the existing
>   `:capability:config` deeplink-provisioning** path that used to carry `S3Config`.
> - Once joined, the device uploads its **photos taken since the event's start date**, scoped to that
>   event, exactly like v1's always-on backup — but to a **per-event key namespace**.
> - The storage credential **no longer ships in the app**. An external **edge upload endpoint**
>   (bunny.net Edge Scripting) **proxies** each upload: the device PUTs bytes to it and it streams
>   them into the bucket via bunny's **native** Storage API, keyed by event id. The device holds no
>   storage secret. *(Consequence — see Honest framing: the edge now **sees the bytes** in transit.)*
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
- iOS app, distributed via **TestFlight** only. **Minimum iOS 18.0** — two upload tiers selected per
  OS version: OS-driven PhotoKit (`ios-photokit-upload`) on iOS ≥26.1, and an app-driven background
  `URLSession` (`ios-url-session-upload`) on iOS 18–26.0.
- **Event-scoped upload** (contribution): a device joined to an event uploads its local
  photos **with capture date ≥ the event start date** → storage, under that event's key namespace.
  Never deletes remotely; no in-app *viewer* (collected photos land in the system Photos library, below).
- **Event-scoped download + import** (`photo-download`): a joined device automatically downloads the
  **other** contributors' complete assets (the event-wide union read, foreign = a `deviceId` other than
  this device's) and imports them **full-fidelity into the system Photos library** (camera roll) — so a
  shared event's photos appear on every participant's phone, without anyone opening the app. Downloaded
  photos are **suppressed from re-upload** (no echo) and never re-imported once deleted. Background
  transfers via a background `URLSession` (Wi-Fi **and** cellular); import preserves the original
  capture date so photos sort by when they were taken. Discovery of *later* additions is foreground-only.
- **One event at a time.** Joining a new event **re-provisions** (replaces) the current one
  (§2.4/§3.2). Multi-event membership is a later concern.
- **Create an event in-app** (enter a name → `POST /events` mints it and the app auto-joins) **or join
  by scanning a QR with the native Camera** → `snapsync://` deeplink → event config provisioned via
  `:capability:config`. Once joined, the app **displays the join QR** for its current event
  (§5/`event-invite-qr`) — a joined device holds the `eventId`, so it re-encodes the same deeplink to
  invite others. It mints no QR images for events it has not joined.
- **Photo assets, whole library, filtered by capture date** — each qualifying asset's **original
  `PHAssetResource`s only** (the original primary + a Live Photo's original paired video; edits,
  adjustments, and the RAW alternate are dropped — `immutable-asset-manifests`). Standalone
  *video assets* are out of scope.
- **Background uploads via `PHBackgroundResourceUploadJobExtension`** (iOS 27+) — the system
  schedules and **performs** the uploads on the app's behalf, even when suspended/locked. (§3.)
- **Uploads are proxied** by an external edge endpoint: the device PUTs to a deterministic per-resource
  URL and the endpoint streams the bytes into bunny.net Storage via its **native** Storage API.
  **The device holds no storage credential.**
- A **local desktop (JVM) test app** — phone-frame preview + side-by-side control panel
  (display overrides + engine console; §5.1).

- **Leave is the local-only inverse of join** (§3.2): a joined device can leave the event — the
  producer is disabled, the ledger wiped, the discovery cursor cleared, and the `eventId` forgotten
  from the Keychain — returning to the create-event screen. It is **local-only**: already-uploaded objects
  stay in storage, so re-scanning the same QR re-joins and reconciles them back (no re-upload).
- **Invite by showing the join QR** (`event-invite-qr`): in the joined layer the status screen shows
  the event's join QR ("Scan to join this event") and a share action — the deeplink is re-encoded
  from the stored `eventId` (`encodeConfigUrl`), so any joined participant can invite others without
  the external tool. The displayed QR **is** the join capability (any scanner becomes an uploader; an
  existing member re-scanning reconciles and uploads nothing new) — an accepted trade-off for a
  personal TestFlight app.

**Explicit non-goals / deferred:**
- **Leave does not delete remote objects.** Leaving forgets the event on-device only; removing the
  event's already-uploaded objects from storage is out of scope (no backend delete path).
- **No in-app *viewer*.** Download-and-import **is** in scope (`photo-download`, above), but collected
  photos are imported into the **system Photos library** and viewed there — the app renders no gallery
  of its own. Edits/adjustments are not synced (originals only); a downloaded photo deleted locally is
  not re-imported.
- **No multi-event membership.** Event creation in-app and the joined-event invite QR
  (`event-invite-qr`) now exist; what remains out of scope is multi-event and any QR generation beyond
  the joined event's own invite.
- No Android app yet (architecture keeps the door open).
- No encryption (plaintext upload). **The edge endpoint sees the bytes in transit** (device→edge→bucket)
  — a deliberate trade of the v1 byte-blind direct-to-bucket path for sidestepping presigned-PUT signing
  on the background extension (§4). No at-rest or in-transit-to-edge encryption beyond TLS.
- No **edit** sync (downloads pull originals only, one way into the library), no remote deletes, no content-dedup.
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
  (`SyncEngine` + its SQL ledger, the engine's only state), the status projection (derived read-only
  from the extension's ledger, notify-driven; §2.4), and the MVI presentation layer.
  Knows nothing about the platform or the event.
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
(§2.2). Event scoping (the `<eventId>/` key placement, the capture-date discovery
filter, the edge URL build) lives **above and beside** the seam, in the platform adapter and the
`UploadRequestProvider` impl — the engine and ledger are event-blind by construction.

### 2.1 Module graph

```
:domain:engine         the shared sync vocabulary + logic; no platform deps:
                         • platform seam (§2.2), resources-only: Resource (concrete value type
                           with opaque platform payload + version), SyncEvent, SyncDecision,
                           UploadJob, UploadRequest, UploadError, UploadRequestProvider.
                           (Asset layer = a slice above.)
                         • SyncEngine — the ledgered decision core (§2.2) + its ledger
                           (LedgerBackend storage seam, LedgerReader/LedgerWriter, SQLDelight). The
                           ledger is the extension's PRIVATE upload memory — the app no longer
                           reads it for status (ledger-free-status).
:domain:status         → :domain:permission + :domain:gallery + :capability:rejoin (the EventFilesSource
                         completeness listing) — ALL implementation-scoped, none leaks to status's
                         consumers, and NO :domain:engine dependency. The status projection (§2.4):
                         SyncStatus + SyncState + SyncStatusSource (snapshot seam, §2.3) and the
                         LedgerBackedSyncStatusSource — read-only ledger aggregates (completed +
                         pending, count-only seam) × permission × own-device gallery total →
                         snapshots; notify-driven refresh (no storage LIST for upload status).
:domain:permission     PermissionStatus / PermissionStatusSource / PermissionRequester.
:capability:config     deeplink → EventConfig provisioning (eventId/name/startDate). Was S3Config in
                         v1; now carries the event. Stores into shared Keychain (app + extension).
:capability:event-creation-ui  the in-app create-event flow: EventCreator (command) +
                         CreationStatusSource (state) seams, CreationStatus, the HTTP creator
                         (POST /events), and the CreateEvent use-case (mint → provision-like-a-QR).
:domain:presentation   → :domain:status + :domain:permission. Orbit MVI container(s) + UiState.
                         COMPOSE-FREE. NO engine dependency — engine types never reach
                         presentation's compile classpath.
:domain:ui             → :domain:presentation + :domain:ui:components. Compose screens, written
                         exclusively against the App* design system.
:domain:ui:components  semantic App* components + the Material 3 skin — the ONLY module allowed to
                         import Material 3 (§5).

:app:desktop           shared harness library: PhoneFrame + StatusPane (the StatusContainerHost
                       wiring both harnesses reuse). Parent run task :app:desktop:run is reserved
                       for the full-stack world harness (engine console — §5.1).
:app:desktop:ui        forge harness (:app:desktop:ui:run): phone-frame preview + display-override
                       control panel (§5.1); depends on :app:desktop.
:app:ios               wires the iOS adapter + Darwin Ktor engine + framework export → iosApp/

iosApp/                Xcode project (not Gradle): the app target (Swift host + Info.plist, registers
                       the snapsync:// scheme) AND a PHBackgroundResourceUploadJobExtension target
                       (Generic Extension; extension point com.apple.photos.background-upload;
                       BackgroundUploadURLBase = the edge host). The :app:ios framework is
                       embedded in BOTH targets (the extension needs the URL-builder provider + config). App
                       and extension share an App Group container + a shared Keychain group.
```

Dependency flow: `:domain:engine ← status ← presentation ← ui`; app modules wire the concrete platform
adapters. Every boundary is **compiler-enforced**, and the backend swap is **structural**
(`:app:ios` wires the PhotoKit-backed adapter + the URL-builder `UploadRequestProvider`; the
desktop wires the engine console).

The `:capability:s3` hand-rolled **SigV4 presigner is retired** (it ran on-device in v1). With the
device credential-free, **all storage auth moves to the external edge endpoint**; the on-device
`UploadRequestProvider` becomes a thin **local URL builder** (no network — §4). `:capability:gallery`/
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
asset→resource fan-out, the **filename layout** (`<assetId>-<role>.<ext>` encodes resource identity
within the device), the **event/device key placement**, and asset-metadata are all **the
platform/provider's** business. The engine carries `assetId` through to the ledger but **never
interprets it** — like `filename` it is pure identity whose meaning is the platform's (iOS: the
asset's `localIdentifier`, normalized). The iOS enumerator producing these facts is split into a
**decision-free `RawAsset` walk** (PhotoKit, iOS-only) and a **pure `commonMain` fan-out mapping**
(`resourcesFrom` — originals filter via `resourceRole`, `uploadKey`, `/`→`_`, manifest metadata), so the
fan-out orchestration is JVM/simulator-tested against a fake walk rather than trapped in the adapter
(`:domain:gallery`, capability `gallery-status`). The platform hands each resource a single opaque `filename`
— pure *identity*, a plain string. Its *representation* and *placement* (percent-encoding into a URL
path, the `<eventId>/` prefix, the edge URL build) are **the provider's
responsibility**, under one contract: the filename→destination mapping must be **deterministic and
injective** — that is where upload idempotency lives.

```kotlin
class Resource(                              // concrete domain type, platform-constructed
    val filename: String,                    // identity; layout + event/device placement is the
    val assetId: String,                     //   platform/provider's (iOS: "<assetId>-<role>.<ext>")
                                             // opaque grouping id (iOS: normalized localIdentifier);
                                             //   engine carries it to the ledger, NEVER interprets
    val contentType: String,                 //
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
    class Upload(override val job: UploadJob) : Work      // not (provably) uploaded yet (new key)
    class Retry(override val job: UploadJob) : Work       // answer to UploadFailed, attempt + 1
    data object AlreadyUploaded : SyncDecision            // ledger proof: nothing to do
}

class UploadRequest(                         // complete, executable: PUT resource → url
    val url: String,                         // the edge endpoint URL for this resource (built locally)
    val headers: Map<String, String>,        // exactly these headers (Content-Type; no custom metadata)
    val resource: Resource,                  // rides whole for the failure round-trip
)
class UploadJob(val request: UploadRequest, val attempt: Int)
                                             // attempt: 0 = create platform job, >0 = retry

interface UploadRequestProvider {            // impl: a LOCAL URL builder, no network (test: dumb fake)
    suspend fun provide(resource: Resource): UploadRequest
    // builds the key (<deviceId>/<encoded filename>) from device identity, composes
    // the edge URL (/files/devices/<deviceId>/<filename>), returns the full request. NO network call.
    // CONTRACT: filename → destination is deterministic and injective; Content-Type set; called only
    // for Work answers — never on a skip.
}

interface LedgerBackend {                    // storage seam: dumb row store, last write wins
    val changes: Flow<Unit>                  // ding after every put; "re-read the truth" —
                                             //   IN-PROCESS ONLY: the ledger is the extension's
                                             //   private memory, no cross-process (Darwin) ding
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
class LedgerWriter(backend) : LedgerReader
                                             // recordRequested / recordCompleted / recordFailed
                                             //   (each takes assetId); deleteByAssetId / retainAssets;
                                             // records no timestamp — engine, writer, and backends
                                             // are all clock-free and store verbatim.
                                             // ONE writer per platform, by construction: only the
                                             // engine-hosting composition root constructs the WRITER
                                             // (the extension). The app constructs no LedgerWriter;
                                             // it reads the ledger read-only for status (§2.4).
class LedgerEntry(key, assetId, state /* REQUESTED|COMPLETED|FAILED */, attempt)
class LedgerAggregates(pending, completed)   // counted by PHOTO (assetId)
                                             // schema: key PRIMARY KEY, assetId (+index), state,
                                             // attempt (SQLDelight typed columns, adapters hidden
                                             // in one factory)

class SyncEngine(provider: UploadRequestProvider, ledger: LedgerWriter) {
    suspend fun handle(event: SyncEvent): SyncDecision    // ResourceChanged = pure query (no write)
    // ResourceChanged(r):  ledger absent/FAILED                  → Upload(mint, attempt = 0)
    //                      COMPLETED/REQUESTED                    → AlreadyUploaded (done/in flight;
    //                                                               an uploaded resource is immutable)
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

**Engine behavior** (ledger-authoritative, write-after-act). `ResourceChanged` is a
**pure query** — it reads the ledger and mints a request for `Work` answers but **writes nothing**. A
key is skipped when the ledger holds it `COMPLETED` **or** `REQUESTED`: an uploaded resource is
**immutable** (a `COMPLETED` key is never re-uploaded — there is no content version), and `REQUESTED`
means **a job is in flight**, so re-deriving the change feed is idempotent. This is sound
only because `REQUESTED` is recorded **after** the platform creates the job: the three lifecycle
events — `UploadStarted`→`REQUESTED`, `UploadFailed`→`FAILED`, `UploadCompleted`→`COMPLETED` — are the
**only** ledger writers, each an unconditional idempotent upsert. A crash between create and
`UploadStarted` leaves no `REQUESTED`, re-issued later as a **bounded, idempotent duplicate** (one
extra upload) rather than a stranded photo. **Retry forever** — no attempt budget; every retry
re-derives the request **locally** (a stable edge URL — no expiry to heal). Provider failures (e.g.
malformed resource input) **rethrow** from `handle()` with
the **ledger untouched**; the event counts as unprocessed and re-handling is safe. **Sequential
contract:** at most one `handle()` in flight per engine.

**Platform contract.** Act on decisions: `Work` → execute the job, **then report `UploadStarted(job)`**
(`attempt == 0` → create a platform job; `> 0` → retry the existing one *or* acknowledge-and-recreate);
`AlreadyUploaded` → continue. On `limitExceeded` the platform stops creating jobs for the cycle, **does
not advance its discovery cursor**, and returns a *processing* result so it is re-invoked. **Report
completions at the acknowledge edge, BEFORE acknowledging** (`UploadCompleted(job)` → then
`acknowledge()`). Failures are reported as `UploadFailed(job, error)` and answered with `Retry`. **Every
presented job is acknowledged** (iOS errors 50008 otherwise). **Retention is the ledger itself** — a
returned system job is mapped back to its key by **parsing its destination URL path**
(`/files/devices/<deviceId>/<filename>` → `<deviceId>/<filename>`), since `resource` is **nil for succeeded
jobs**; the attempt comes from the ledger row. **One ledger
writer per platform:** the engine (and its `LedgerWriter`) is hosted where uploads are decided — on
iOS, the extension, which is the ledger's sole owner (the app reads no ledger; §2.4). Scope filtering (photos yes, standalone
video no; **capture date ≥ event start**) sits above the seam — the engine is media-type- and
event-blind by construction.

**Accepted costs** (eyes open): a crash in the write-after-act window yields **one bounded, idempotent
duplicate** upload on the next re-derivation — never a stranded photo. Retry-forever churns a job slot
on a permanently-broken resource (now also covers a permanently-failing edge endpoint). The
system-surfaces-all-results assumption means a silently-dropped job would leave a `REQUESTED` row
**stranded** — a full re-enumeration does **not** rescue it (the engine skips `REQUESTED` keys), so it
clears only on a disable's `clearRequested()` or when the asset is pruned (deletion / retain); deferred
until observed on device.

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
- Platform signals (`photoLibraryDidChange`, foreground entry, and the **extension's cross-process
  liveness notification** — a Darwin ding posted after each `process()` run) and **event-config
  changes (join)** are **invalidation dings handled inside the iOS impl** — each triggers a re-read
  (re-read the ledger counts / re-count the gallery) + a fresh emission; none leaks into the contract.
  (The liveness ding is posted by the extension **composition root**, not `LedgerBackend`, whose change
  flow stays in-process.)
- One-shot effects (toasts, later) are derived **downstream** by diffing consecutive snapshots in the
  Orbit container.

### 2.4 Status projection: own-device ledger truth, notify-driven

How `SyncStatusSource` (§2.3) gets its truth. The UI seam stays a level-triggered
`StateFlow<SyncStatus>`; behind it, `LedgerBackedSyncStatusSource` (in `:domain:status`) derives status
from the extension's ledger, the permission seam, and the live gallery, minting snapshots
(`notify-driven-status`, 2026-07-05 — a deliberate reversal of `ledger-free-status`'s upload-completeness
path, justified by the invariant below):

- `completed` **and** `pending` ← the extension's shared App-Group **ledger**, read **read-only** from a
  single consistent `aggregates()` round-trip: `completed` = photos all of whose ledger rows are
  `COMPLETED`; `pending` = photos with any non-`COMPLETED` row (the asset-counted "uploading now" set).
  Both are asset-counted; the two sets are disjoint (an undiscovered photo is in neither). `pending` is
  **clamped to remaining** (`min(pending, total − completed)`) and **display-only** — it feeds the
  caption, **never** classification. The `LedgerCountsSource` seam exposes **counts only**; the
  composition root injects the read (`suspend () -> LedgerCounts`), so `:domain:status` keeps **no**
  `:domain:engine` dependency and the extension stays the **sole `LedgerWriter`** — the app builds none;
  its one ledger write is the app-side reset-family `clearRequested()` on extension disable (§2.2,
  capability `sync-ledger`).
- `total` ← the **own-device gallery count** (`GalleryStatusSource`: enumeration minus downloaded
  foreign photos; enumeration-only, **no storage LIST**).

The factory is non-suspending: it seeds `Loading` and, on its scope, combines the three inputs, emitting
`Ready` once the ledger counts + permission + gallery have each produced a first value. **Liveness is
event-driven, not polled:** the composition root re-reads the ledger counts on **foreground entry**, on
the extension's **cross-process Darwin liveness notification** (posted after each `process()` run;
observed **foreground-only**), and — on the app-driven tier — after **each in-process pump cycle**. Each
re-read is a **local** ledger read, **no network**. A failed ledger read **retains the last good counts**
(never regressed to 0, so a transient error can't flip the screen out of "In sync").

Reading the ledger for classification is safe under the **no-deletion-during-an-active-event invariant**:
storage is never reset or pruned while an event is active, so a `COMPLETED` row always maps to a durable
object and the ledger cannot over-count. The sole ledger↔storage divergence point — (re)join — is
reconciled by the extension's `event-rejoin-reconciliation` (already-stored photos seeded `COMPLETED`
before enabling). The `observed-completion-overlay` (which once masked ledger lag) stays **deleted** —
per-run freshness via the ding is sufficient. The **download** direction is unchanged: its foreign-object
reconcile is driven by the remote APNs silent push (`notify-driven-download`) + foreground.

- **Counts are by PHOTO (assetId), not resource row**: `completed` = photos fully `COMPLETED` in the
  ledger, `pending` = the clamped in-flight ledger count. The status surface reports completeness and
  live activity only — there is **no completion timestamp** (no "last backed up N ago"). Under the
  no-deletion invariant `completed` only ever climbs. `failed` ≡ 0 (retry-forever) and
  `estimatedRemaining` ≡ null (never estimates) — both fields exist for classification and fakes.
- **Classification is counts-only** — a pure function of the live total `N` and the clamped synced
  count `n = min(completed, total)`: `total == 0 → NOTHING_TO_SYNC; n >= total → COMPLETE; else
  IN_PROGRESS`. There is no SUSPENDED state (the setup gate shadows every inactive case), no
  NEVER_SYNCED (it folds into IN_PROGRESS at `n = 0`), and no FAILED state (untellable under
  retry-forever).
- **`active` = operational state** (not a liveness heuristic): *contribution machinery is allowed to
  run* — `permission == GRANTED` **AND a valid event config is present (joined)**, derived once inside
  the ledger-backed source. Shared logic, no clocks. Consequence: **the setup gate covers the hero
  whenever `active` is false** — i.e. when permission isn't granted **or** when no event is joined
  (§5). "Not joined" is therefore **the gate's no-event state**, exactly mirroring v1's no-storage-setup
  gate; it is not a new top-level `SyncState`.
- **Cross-process topology (iOS):** the extension hosts the engine and the single `LedgerWriter` (WAL,
  short single-statement writes); the app is a **read-only** reader (`aggregates()` peek for status +
  the reset-family `clearRequested()` on disable). After each `process()` run the extension composition
  root posts a **payload-free cross-process Darwin liveness notification**; the app observes it
  **foreground-only** and re-reads the ledger counts (a local read, no network). The notification is a
  bare "re-read the truth" signal — it carries nothing and refreshes only the display-facing counts, so
  the single-writer invariant and classification-safety are untouched. `LedgerBackend` itself still
  posts no cross-process ding (its change flow is in-process).
- **Staleness detection is read-only:** the app compares the photo library's `currentChangeToken` with
  the platform bookkeeping's last token — mismatch ⇒ undiscovered work ⇒ status can say "waiting for
  system" instead of a false COMPLETE (later slice).
- **Joining (re)provisions** (§3.2): the app persists the new event config and (re)enables the
  producer; reconciliation itself runs **inside the extension** (the `event-rejoin-reconciliation`
  capability), gated by a persisted `joinedEventId` marker. On its next cycle the extension compares
  the configured event to the marker: a **different** event resets its private ledger and re-seeds, the
  **same** event is a no-op, and a fresh provision seeds already-stored photos as `COMPLETED` so they
  are not re-uploaded. Status is read from the ledger (§2.4), which this seed populates — reconciling
  the ledger against storage at (re)join is the load-bearing step that keeps ledger-sourced status
  honest (already-stored photos show `COMPLETED` immediately, not re-uploaded). Uninstall leaves an
  absent marker → the next cycle reconciles. Re-sync is idempotent.

---

## 3. Sync design

### 3.1 Object keys (event/device-scoped, metadata in the path)

> **Update (2026-06-29, `dedup-files-device-manifests`) — supersedes `flatten-event-namespace` +
> `immutable-asset-manifests` below.** Bytes now live in a **device-partitioned, event-independent**
> store and are *linked* into events by reference (uploaded once, reused across events):
> ```
> /files/devices/<device-id>/<assetId>-<role>.<ext>  bytes · device-global · uploaded once
> /events/<event-id>/metadata.json                   event marker {eventId,name,createdAt}
> /events/<event-id>/devices/<device-id>.json         the device's per-event manifest (one doc, not N)
> ```
> - **device-id returns** (reversing `flatten-event-namespace`): a per-install UUID minted in the
>   shared Keychain (`device-identity`), the `/files/devices/<id>/` partition and the manifest key — recorded now as
>   forward-prep for a deletion-correct restore (still out of scope). Content-hash keys remain
>   impractical (the OS still never shows the extension the bytes), so dedup is **same-device,
>   across-events** via the device-local `assetId`, not cross-device.
> - **The per-asset manifest becomes one mutable per-event `device.json`** (reversing
>   `immutable-asset-manifests`): a full-state projection of a device-global accumulator, **PUT
>   synchronously in-cycle** by the extension (no background `URLSession`), deletion-aware, write-only
>   in v1. The write-once / permanent-cache property is dropped.
> - **Byte uploads are ungated** (`PUT /files/devices/<id>/<file>`) — accepted abuse trade-off; the
>   event-existence gate moves to the device.json write. **Status is own-device**: gallery enumeration
>   (expected) × the per-device file listing `GET /files/devices/<id>` (present); it reads no device.json.
> - **Reconcile** on a re-join **`resetTo`s** (atomic clear-and-seed) the ledger from the per-device
>   listing — one `COMPLETED` row per stored filename — and **clears the discovery cursor** to force a
>   full re-enumeration. The clear drops stale/phantom rows (e.g. a `REQUESTED` row from a prior cycle
>   whose job never materialized). Cross-event dedup still holds: the device listing is event-independent,
>   so a switch re-seeds the same files `COMPLETED` and re-uploads nothing already stored; a leave clears
>   only the join marker (the ledger stays intact for re-join dedup). The key is the bare,
>   event-independent filename.
>
> The narrative below is the prior (pre-update) design, retained for context.

The upload-job API fixes the **destination URL at job-creation time**, but the **system** reads the
bytes during upload — so we never see the bytes and **content-hash keys are impractical**. v1 used
per-resource metadata keys; this version namespaces them under the event and the contributing device.

We upload **only each asset's original resources** (reversing v1's "complete fidelity" —
`immutable-asset-manifests`): the original `.photo`/`.video`/`.audio` and, for a Live Photo, the
original `.pairedVideo`. **Edit artifacts are never uploaded** — `.fullSizePhoto`/`.fullSizeVideo`/
`.fullSizePairedVideo` renders, `.adjustmentData`, `.adjustmentBase*`, the RAW `.alternatePhoto`, and
proxies are all dropped — so an asset's resource set is **fixed at capture and never grows**.
"Qualifying" = the asset's **capture date ≥ the event start date** (§3.2).

- **Key: `<eventId>/<encoded filename>`** (no `events/` prefix — the zone is the event
  collection) where the filename is **`<assetId>-<role>.<ext>`**, composed platform-side:
  - **`<eventId>`** — from the joined event config (deeplink, §4). **High-entropy/unguessable** — it
    doubles as the upload capability, since the edge endpoint authorizes by event id alone (no token).
  - **`<assetId>`** = the asset's `PHAsset.localIdentifier` with `/`→`_` (**no `PHCloudIdentifier`
    resolution** — dropped with its batch-lookup cost and provisional-window risk). `localIdentifier`
    is UUID-based, so it is sufficient as the per-event identity — no cross-device dedup is attempted,
    and no device namespace is needed (see the collision note below).
  - **`<role>`** = a generic, platform-neutral role — `primary` (the single original primary medium:
    still, video, or audio) or `live` (a Live Photo's original paired video). Whether the primary is
    an image or a video is carried by `Content-Type` (the resource's UTI), **not** the role.
  - The filename is pure *identity*; the **provider** owns its representation — percent-encoding (bytes
    outside `[A-Za-z0-9._-]` → `%XX`) and the `<eventId>/` placement — under the
    deterministic-and-injective contract (§2.2). The bucket is **flat within that prefix**.
- **Per-asset manifest + read-time completeness (`immutable-asset-manifests`).** For every asset the
  producer also uploads one **manifest** object `<eventId>/<assetId>.manifest.json` (`version`,
  `assetId`, `creationDate`, `resources[]{role, contentType, filename, originalFilename}`) — the
  authoritative declaration of the asset's complete resource set. Because the OS owns background-job
  scheduling and the manifest cannot be "uploaded last," **completeness is computed at read time**: the
  list endpoint (`GET /events/<id>/files`, §4) reads each manifest and returns an asset only when every
  resource it names is present. The manifest is **not** an engine `Resource` and **not** in the ledger
  — it rides a vanilla background `URLSession` side channel (the OS job API carries only a
  `PHAssetResource`); the extension generates + enqueues it and the **app** handles its completion
  (`handleEventsForBackgroundURLSession`), with an App-Group PENDING/DONE file as the dedup/retry
  marker. **Change 2 (`ledger-free-status`, applied):** the app's status projection reads this same
  completeness listing (plus the on-disk in-flight manifests and the gallery total) instead of the
  ledger — see §2.4.
- **Edit-handling dissolves:** the original resource is immutable and the only thing uploaded; an edit
  produces no new upload — its render/adjustment artifacts are dropped, and the manifest (originals
  only) is never revised. Nothing is overwritten or added.
- **No custom metadata headers.** bunny Storage's native API **does not support custom metadata
  headers** (§4), so v1's signed-header reconstruction scheme is **removed**. The downstream
  (external) viewer recovers what it needs from **(a)** the key path (event, resource kind, original
  identity) and **(b)** the **EXIF/maker metadata already inside the image bytes** (capture date,
  geolocation, camera). There is **no signing at all** — the edge writes with its `AccessKey`; the
  device sends only `Content-Type`.
- **Flat namespace, no device level (`flatten-event-namespace`).** Keys are not scoped by a device id.
  The earlier `<eventId>/<deviceId>/` scheme hedged against a `localIdentifier` collision across
  devices, but `localIdentifier` is UUID-based: in a flat `<eventId>/` namespace two devices collide
  only on the *same* `localId`, which is either the same physical asset (identical bytes → harmless
  idempotent overwrite) or a UUID collision (~0). The device level bought only anonymous
  per-contributor grouping for an out-of-scope external viewer — not a v1 goal — so it is removed.
  **Foreclosed (accepted):** per-contributor grouping and clean per-device deletion; reintroducing
  either later needs a contributor id folded into the filename, not a directory level.
- Trade-offs (accepted): **no content dedup** — the *same* physical photo shared by two devices lands
  once per distinct `localId` (typically twice, since `localId` is device-local). Asset-level facts
  not present in EXIF (favorite flag, album membership) are **not preserved** (cosmetic; deferred).

### 3.2 Discovery & state (date-filtered PhotoKit discovery, engine-owned memory)

> **Migration scope (v1 of the bunny pivot, `migrate-ios-upload-to-bunny`):** date-filtered
> discovery is **deferred** — the shipped pivot enumerates the **whole library** (no `creationDate`
> predicate), and the deeplink carries **`{ eventId }` only** (no `startDate`/`name`; §4). The
> date-filter design below is the target end-state; it returns once `startDate` is provisioned. The
> credential-free edge upload and the `<eventId>/` placement ship now (the device level was later
> removed — `flatten-event-namespace`).

- **Discovery** is the `PHPersistentChangeToken`, **filtered to capture date ≥ event start**:
  - **Initial join:** `PHAsset.fetchAssets` over the library with a `creationDate >= startDate`
    predicate + capture `currentChangeToken` as the baseline cursor.
  - **Steady state:** `fetchPersistentChanges(since:)` yields change **records**, each carrying its own
    serializable token (durable cursor advances **per record**); newly-relevant assets are filtered by
    `creationDate >= startDate` before fan-out. Assets captured **before** the start date are ignored,
    even if added/changed later.
  - **Token expiry is routine** (`persistentChangeTokenExpired`) — the remedy is full (date-filtered)
    re-enumeration, harmless because the ledger answers `AlreadyUploaded` for everything already done.
- **Joining (re)provisions**: on a deeplink the app persists the new `eventId` and (re)enables the
  producer — it runs **no** join, fetch, or seed, and constructs **no** ledger type. Reconciliation
  lives **inside the extension** (`event-rejoin-reconciliation`), gated by a persisted `joinedEventId`
  marker in the App-Group `NSUserDefaults` (the marker, **not** ledger-emptiness, is the join signal —
  ledger-emptiness cannot work in the extension's short-lived per-cycle process: a zero-row join would
  never settle). On its next cycle, **before** creating any upload job, the extension compares the
  configured `eventId` to the marker: equal ⇒ upload directly; different (a switch, reinstall, or fresh
  provision) ⇒ fetch the event's complete-asset listing, atomically `resetTo` one `COMPLETED` row per
  stored resource (the reset replaces any prior event's rows), clear the discovery cursor, **reset the
  per-asset manifest markers** (they are `assetId`-keyed, not event-scoped, so a switched event must
  re-upload its manifests or its assets never read as complete), and set the marker — even a zero-row
  join sets it, so there is no re-seed loop. If the listing fetch fails the
  extension creates no jobs that cycle and leaves the marker unset, retrying on its own cadence (no
  user-facing join-failure state; status comes from the app's own LIST). A re-join thus re-uploads
  **nothing** already stored — only genuinely-un-stored photos upload, on the OS's next invocation.
- **Leaving** (`leave-event`) is the local-only inverse: a tested `LeaveEvent` use-case runs, in order
  and best-effort, `disable producer → ConfigStore.clear()` — only. It touches **no** ledger, cursor,
  or marker (and constructs no ledger type); the extension resets its own private ledger, cursor, and
  marker on its next cycle once the configured event no longer matches the marker. It touches **no
  storage** — already-uploaded objects remain, and a later re-scan re-joins and reconciles them back.
  The producer-disable side-effect is an injected lambda, so the use-case stays in a tested capability
  and `:app:ios` keeps wiring-only.
- **State**: upload memory is the **engine's ledger** (single writer = the extension). The platform
  keeps only small, **lossy-tolerant** discovery bookkeeping in the App Group: `{lastToken,
  startDate, eventId, deferredIds?}`. Losing the residue costs one record's worth of
  duplicate jobs, absorbed downstream. The job system remains the execution authority
  (`acknowledge()` frees `jobLimit` slots). The app never `LIST`s the bucket (the edge endpoint is
  PUT-only); restore/view-side `LIST` is a separate external admin path (§4).

### 3.3 Flow

> Phases below carry over the device-verified v1 model (§2.2), with two deltas: discovery is
> **date-filtered to the event start**, and the destination URL **points at the external edge
> endpoint** (built locally per resource by the `UploadRequestProvider`) instead of signed on-device.

**App (foreground):** request `.readWrite` photo authorization → (on a valid joined event config)
`setUploadJobExtensionEnabled(true)` → show status (job states + App-Group progress). **The app
uploads nothing itself.** A new event deeplink re-provisions (§3.2). There is **no enable/disable
toggle and no Leave** — contribution is on whenever permission is granted *and* an event is joined.

**Extension — `processJobs()` (system-invoked, hosts the shared `SyncEngine` + `LedgerWriter`):**
the system downloads each resource (incl. from iCloud) and performs `job.destination` with the
**resource bytes as the request body**. We manage the queue in three phases:
1. **Adjudicate failures** — `fetchJobs(action: .retry)`; produce the `UploadJob` (key from the
   destination URL; attempt from the ledger), map the error → `UploadError`, report
   `UploadFailed(job, error)`; the engine answers `Retry` (`attempt + 1`). The `Retry`'s request is
   rebuilt **locally** (a stable edge URL — nothing to re-mint or expire). ⚠️ **One retry per
   system job only**: first failure → `retry(destination: fresh URL)`; already-retried → `acknowledge()`
   + re-create a fresh system job.
2. **Acknowledge completed** — `fetchJobs(action: .acknowledge)`; report `UploadCompleted(job)` (engine
   records `COMPLETED` — write-then-act), **then** `acknowledge()`. A crash between the two re-presents
   the job → a duplicate report, absorbed by the idempotent ledger.
3. **Create new jobs** — discovery (§3.2, date-filtered). For each qualifying asset, expand to its
   **original** `PHAssetResource`s and wrap each as a `Resource` — filename `"<assetId>-<role>.<ext>"`
   (no content version: an uploaded resource is immutable). Report `ResourceChanged` per resource and act on the decision:
   `AlreadyUploaded` → continue (no job slot); `Work` → `provider.provide(resource)` **builds the edge
   URL** (`/files/devices/<deviceId>/<filename>`) locally, then take the `PHAssetResource` from
   `decision.job.request.resource.data`, build the `URLRequest` (PUT + `Content-Type`) →
   `creationRequestForJob(destination:resource:)` in `performChanges {}`. On `limitExceeded` stop
   reporting for this cycle, do **not** advance the cursor, and return `.processing`.
4. Return `.completed` / `.processing` (incl. while the ledger still has pending rows, to flush
   completions) / `.failure`. `willTerminate()` cancels the in-flight collection.

**No URL expiry:** the system may run a job much later, but the destination is a **stable edge URL**
built locally per resource — no presign, no expiry, no re-mint. A retry just re-PUTs the same URL.

> ✅ **Resolved — the UNSIGNED-PAYLOAD TOP RISK is gone.** Under the proxy the *edge*, not the device,
> writes to bunny (native API + `AccessKey`, no payload hash). The background extension only PUTs bytes
> to the edge URL, so there is no on-device signature to break. This is the whole reason for the pivot.
>
> ⚠️ **Still verify on device / in Xcode** (iOS 27 API): the system accepts the edge **destination URL**
> under `BackgroundUploadURLBase` = the edge host; whether it sends an **`OPTIONS` resumable-upload
> preflight** to the edge and falls back to a plain PUT (v1 verified raw S3 needs none — re-verify);
> which `2xx` it treats as success; required **entitlements**; `PHAssetResource ↔ job ↔ key`
> association; `creationRequestForJob` semantics; and **edge reachability/latency from the extension**.

### 3.4 Why this shape

iOS 27's `PHBackgroundResourceUploadJobExtension` lets the **OS schedule and perform** uploads
(power/network-aware, across suspension/lock) — dissolving temp-file handling, throttling, manual
`URLSession`, and the periodic-trigger problem. We contribute only: **which assets** (date-filtered
change feed), **where** (a destination URL **pointing at the external edge endpoint**, built locally), and
**retry/acknowledge** policy. The extension links the shared framework and reads the joined event
config from the shared Keychain + bookkeeping from the App Group.

### 3.5 Downstream reconstruction (external, out of scope)

There is **no sidecar and no app-side upload** — and now **no custom metadata headers** either (§3.1,
unsupported by bunny native API). The collected photos are made sense of **externally**:

- The **key path** carries event id, resource kind, and the resource's original identity.
- The **image bytes carry their own EXIF/maker metadata** (capture date, geolocation, camera, etc.).
- An external tool (admin-credentialed, `ListBucket` + `GetObject`) can read storage directly to
  enumerate all contributions and read EXIF per object.

The **event-creation tool**, the **QR minting**, and any **viewing/restore tool** are all **external,
out of scope** for this app. This doc specifies only the contracts the app depends on (§4).

**Event-wide union read (now on the edge).** The earlier deferral of the event-wide union to an
external/admin-direct reader is **reversed**: because a future on-device download/restore client holds
**no storage credential**, the union is exposed as an **edge** read, `GET /events/<eventId>/files`
(capability `bunny-list-endpoint`). It returns, for one event, every contributing device's **complete**
assets (an asset is complete only when every resource its `device.json` names is present in
`/files/devices/<deviceId>/`), flattened across devices, each tagged with its owning `deviceId` — the client
skips its **own** device by `deviceId` (the endpoint is identity-blind). Each resource carries
`{ role, contentType, key, filename, size, url }`, a straight projection of the per-event device
manifest (`device.json`) — except `url`, which is a **presigned S3 GET URL** the edge mints per object
(SigV4, 7-day expiry) so the download client fetches the bytes **directly from bunny's S3-compatible
endpoint**, off the backend (§4). Event-gated on the marker, strictly faithful (any non-404 read
failure in the fan-out → `502`, never a partial union), and non-cacheable. The download/import client
that consumes this union is **implemented** (`photo-download`): it selects foreign complete assets and
imports them into the library, downloading each resource directly from its presigned `url`.

---

## 4. Storage, auth & config

The device holds **no storage credential** (the v1 embedded IAM key is gone). Authorization to upload
is mediated by an external **edge upload endpoint** that **proxies** bytes into storage. This pivots
away from the earlier mint/presigned model — which carried an unresolved TOP RISK (whether a bunny
S3-compatible presigned PUT accepts `UNSIGNED-PAYLOAD`, since the background extension never sees the
bytes). The proxy sidesteps signing entirely: the endpoint, not the device, writes to bunny.

- **Storage: bunny.net Storage via its native HTTP API** ([docs](https://docs.bunny.net/api-reference/storage))
  — a plain authenticated `PUT https://<region-host>/<zone>/<key>` with header `AccessKey:
  <storage-zone password>`. **No SigV4, no presigning, no payload hash.** DE/Falkenstein default host
  `storage.bunnycdn.com`. Uploads, the event registry (marker/manifest), and both listings all use this
  native API. Known limits we design around: **no custom metadata headers** (§3.1), no
  versioning/ACL/SSE/tagging/lifecycle, no batch delete.
- **Downloads: the zone's S3-compatible API** (`add-s3-presigned-downloads`, 2026-07-02). The
  `snap-sync-dev` zone has S3 compatibility enabled (a create-time-only flag), so the **same objects**
  are reachable over both the native API (writes, listings) and the S3 API — spike-verified: a
  native-written object reads back through an S3 **presigned GET**. The backend returns each listing
  `url` as a presigned S3 GET (`aws4fetch` SigV4, path-style
  `https://<region>-s3.storage.bunnycdn.com/<zone>/<key>`, `X-Amz-Expires` 7 days) that the device
  fetches **directly** — the backend is off the download byte path (**no download proxy route**). The
  zone name is the S3 Access Key ID and the storage `AccessKey` the secret (no extra credential); the
  short-read integrity check moves client-side (against S3's `Content-Length`). Presigned links expire,
  so the device re-presigns on every foreground reconcile (`download-store` refreshes a not-yet-staged
  resource's `url`) and an expired link self-heals. `bunny-list-endpoint` is the sole authority on the
  URL format.
- **Upload endpoint (external, bunny.net Edge Scripting / Deno + Hono — implemented in `backend/`):** the app's
  only backend dependency. Capability specs: `bunny-upload-endpoint`, `bunny-list-endpoint`, `backend-deployment`.
  - **Device-facing origin = a domain we control.** The app talks to `snapsync.stho.net` — a custom
    domain in our Bunny DNS zone, served with a publicly-trusted (Let's Encrypt) cert. The same bundle
    deploys to **both** runtimes; the domain is `CNAME`'d to whichever is **active** — **Deno Deploy
    today** (bunny Edge Scripting is sidelined while bunny investigates dropping iOS's zero-window
    upload SYNs). Because the baked host names a domain we own, swapping the active runtime is a **DNS
    repoint + a server-side `PUBLIC_BASE_URL` flip — not a new app build**.
  - **Request:** `PUT /files/devices/<deviceId>/<filename>` with the resource bytes as
    the body and `Content-Type`. The endpoint **streams** the body straight into the bunny native PUT
    (one subrequest, never buffered).
  - **Read (list):** `GET /events/<eventId>/files` returns a flat JSON array of every stored object
    for the event — `{ filename, size, url }` per entry (no `lastModified`: an uploaded resource is
    immutable and the re-join seed timestamps rows with the join time). It does a **single** bunny native
    Storage LIST of the event dir (files are direct children), authorized by the event id alone.
    `200 []` for an empty/unknown event (no registry to distinguish), `400` for a malformed id, `502`
    on any upstream LIST failure (never a partial list). The listed `filename` is decoded back to the
    uploaded name so a device matches by it. Consumer (implemented): a re-joined device pre-seeds its
    ledger before enabling uploads — a reinstall (and a destructive ledger migration) empties the
    ledger, so it reconciles by the reinstall-stable `filename`. Capabilities: `bunny-list-endpoint`
    (read) + `event-rejoin-reconciliation` (the on-device join).
  - **Response:** `2xx` **only** when bunny confirms the stored object; any upstream error/abort →
    `5xx` (so the engine retries). Never a false success.
  - **Authorization: by event id only** (no token — the QR carries no secret beyond the id). The event
    id is a high-entropy UUID, so possession of it is the capability. The endpoint validates the path
    (UUID `eventId`, safe filename) and writes the **bare** key `<eventId>/<filename>`;
    abuse protection (overwrite rejection, rate-limiting, a registry) is deferred (§8). The storage-zone
    `AccessKey` lives only in the edge env.
  - **Base URL is compile-time-baked** (BuildKonfig `BackgroundUploadURLBase` = the edge host —
    `snapsync.stho.net`, the custom domain we control, never a runtime-provider vanity host), not
    carried in the QR. The per-resource URL is built **locally** by the provider (no mint round-trip).
- **Event creation + QR minting (external, out of scope):** a separate tool creates an event
  (high-entropy id, name, **start date = creation time**) and emits a `snapsync://` QR. Not implemented
  by this app; documented here only as the source of the deeplink payload.
- **Deeplink payload (`:capability:config`):** the QR encodes a **`snapsync://` custom-scheme** URL
  (kept from the v1 deeplink-config slice; accepted: native-Camera handling of custom schemes is less
  reliable than a Universal Link and **dead-ends if the app isn't installed**) carrying
  **`{ eventId, name, startDate }`**. Scanning with the native Camera opens the app, which provisions
  the event into the shared Keychain and re-provisions sync (§3.2). `name` is carried for possible
  future display; the status screen shows **progress only** (§5).
  > **Migration scope (v1 of the bunny pivot):** the shipped payload is **`{ eventId }` only**, on
  > version **`v=3`** (the legacy `v=1`/`v=2` S3 payloads are rejected — an upgraded device falls
  > through to "not joined" and rescans). `eventId` is validated as a canonical UUID at scan.
  > `name`/`startDate` (and the date-filtered discovery they drive, §3.2) are **deferred**.
- **Networking:** the **upload PUT is performed by iOS** directly against the edge endpoint; no
  on-device HTTP client mediates it, and there is **no mint round-trip** (the URL is built locally).
  The **hand-rolled SigV4 presigner is retired** on-device — all storage auth is the edge's job.
- **Build-time config via BuildKonfig**: the **edge host** (→ `BackgroundUploadURLBase`). **No secrets**
  are baked (the storage `AccessKey` lives only in the edge env). The event id/name/start arrive at
  **runtime via the deeplink**, not at build time.

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
  only** — no event name, no enable toggle, no manual "sync now". (Event identity is still deferred.)
- **Three-layer screen model + the leave affordance.** The screen is a progression —
  `loading → gate → joined` (the `UiState` families: `Loading`; `Setup`;
  `InProgress`/`NothingToSync`/`Completed`). There is **no join-status layer**: reconciliation runs in
  the extension and status is read from the completeness listing, so during a (re)join the screen
  simply shows the listing-derived snapshot (typically `InProgress` with a rising synced count) — no
  spinner, no failure screen. A flat, icon-only **Leave** button (Material `Logout`) sits bottom-right
  **only in the joined layer**; it is absent in loading and the gate. Tapping it raises a **"Leave
  event?"** confirmation (confirm/cancel) whose confirm fires the container's `onLeaveEvent()` intent;
  the dialog's visibility is **local screen state**, so no `UiState` variant or reduction branch is
  added. The leave button and the confirm dialog are new semantic `App*` components (`LeaveButton`,
  `AppConfirmDialog`) plus a bottom-right action slot on `ScreenLayout`; the `Logout` glyph stays
  contained in `:domain:ui:components`.
- **Invite affordance in the joined layer** (`event-invite-qr`). In the same joined layer the screen
  shows the event's **join QR** ("Scan to join this event") above the hero and a flat icon-only
  **share** action, alongside Leave. The invite deeplink is re-encoded from the stored `eventId`
  (`encodeConfigUrl`) and exposed by the container as `inviteUrl` — a screen-level param (like the
  transient invalid-link error), so `UiState` and the reduction are untouched. Share is a
  fire-and-forget `share: (String) -> Unit` lambda on the container (the `leave` shape, not a named
  seam): iOS presents `UIActivityViewController`, the desktop harness copies to the clipboard. New
  semantic `App*` components (`AppQrCode`, `ShareButton`); the bottom-right slot becomes a
  container-arranged **action cluster** (share + leave); the QR-rendering library (`qrose`) stays
  contained in `:domain:ui:components`. The displayed QR is the join capability — see §1.
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

The desktop harnesses render **side-by-side**: left = the real `:domain:ui` status screen inside a
fixed **phone-sized frame** (~390×844); right = a **control panel** (utilitarian raw Material 3, never
`App*`). Both panes bind the same Orbit container. The shared left pane — `PhoneFrame` + the
`StatusPane` wiring that constructs the `StatusContainerHost` — lives in the parent `:app:desktop`
**library**; each harness supplies its own seam sources and right pane. There are two harnesses, named
by their run task:

- **`:app:desktop:ui:run`** (module `:app:desktop:ui`) — the **forge** harness: its right pane is the
  display-override control panel below, driving stand-in cells (no engine).
- **`:app:desktop:run`** (the parent) — the **full-stack world** harness: the engine-console section
  below, driving a real `SyncEngine`. *(Reserved for the full-stack build-out; the forge harness
  covers the display overrides today.)*

The two permanent sections:

- **Display overrides:** buttons that forge display state for UI iteration — **Permission** presets
  (write the permission cell), **Joined/Not-joined** presets (forge the event-config gate state), and
  **Sync** presets (write the sync cell and force permission Granted + joined). An armed
  "next request →" control decides what the fake `PermissionRequester` resolves. All mutations go
  through one `PanelController`.
- **Engine console:** an event composer (build `ResourceChanged` test resources; push `UploadFailed`
  with a chosen error; auto-responder with configurable delay/failure modes) driving a **real
  `SyncEngine`**, plus a **jobs journal** pane listing every `UploadJob` (retry chains visible). The UI
  is skin over a plain `EngineConsole` core that tests drive directly. The provider here is the same
  local URL builder (no network in either harness or production) — no live edge calls in the harness.

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

- **Unit (JVM + simulator)** — `SyncEngine` decision tests (skip on proof — a `COMPLETED` key is
  immutable and never re-uploaded, hope-never-skips, retry chains, provider-failure rethrow,
  suffix-replay convergence);
  `LedgerBackend` contract tests against the in-memory and SQLDelight (JVM sqlite) backends incl.
  **`clear()`** for re-provision; the classification decision table and `LedgerBackedSyncStatusSource`
  tests (fake `LedgerCountsSource` + fake permission + in-memory gallery), plus `LedgerCountsSource`
  (keep-last-good on a failed read) and `OwnDeviceGalleryStatusSource` (own total, downloads suppressed)
  tests; **`UploadRequestProvider` (local URL builder)
  tests** — key construction (`<eventId>/<encoded filename>`, percent-encoding,
  deterministic+injective), edge-URL shaping, invalid-input → rethrow; **date-filter discovery
  predicate tests** (capture ≥ start) where the logic is shared; `:capability:config` deeplink-parse
  tests (`snapsync://` → EventConfig); Orbit reducers.
- **The edge endpoint** is tested by its own `Deno.test` suite (`backend/`, against a mocked bunny
  upstream — see `bunny-upload-endpoint`); the on-device provider is a pure local URL builder (no
  HTTP). There is **no on-device SigV4 to golden-test** anymore (the v1 SigV4 golden suite retires
  with the presigner).
- **iOS** — the PhotoKit upload extension (`ios-photokit-upload`) is **physical-device only** in the
  current iOS 27 beta. Plan: **manual on-device testing** now; move into simulator XCTest/CI once Apple
  adds simulator support. The URL build, deeplink provisioning, and date-filter discovery can be
  simulator/JVM-tested earlier. The **app-driven `URLSession` tier** (`ios-url-session-upload`) is by
  contrast **simulator-runnable** (a background `URLSession` runs in the simulator), so its transport is
  drivable end-to-end there — though `BGProcessingTask` scheduling *timing* and true-suspend behavior
  remain device-only.
- **Desktop** — container reduction via `orbit-test` + Compose UI tests on the status screen and the
  two gate states. Panel/`PanelController`/fakes are test equipment (no tests). CI: Compose Desktop UI
  tests render offscreen under `-Djava.awt.headless=true` — **no display / Xvfb needed**.

The ultimate check is the **on-device extension upload** against the edge endpoint (the OPTIONS /
fall-back-to-plain-PUT + accepted-`2xx` verification, §3.3/§8); if a first real upload fails, the
iOS↔edge contract (preflight, success code, reachability) is the prime suspect — the storage write
itself is covered by the endpoint's `Deno.test` suite.

---

## 7. Chosen libraries

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform | single codebase; Material 3 behind a design-system abstraction |
| State | **Orbit MVI** (10.0.0) | Compose-free; Decompose-able later |
| DI | **Manual composition root** | no deps, compile-safe; Koin if it grows |
| HTTP | **Ktor** (Darwin engine) | **not load-bearing for upload** — the PUT is iOS's (straight to the edge) and the `UploadRequestProvider` builds the URL **locally** (no HTTP). `GET /events/<id>/files` (the completeness listing) is used **two** places: the app's status read (§2.4) and the **extension's** rejoin reconcile seed |
| Crypto/IO | **okio** (App-Group IO) | hand-rolled SigV4 **retired** — signing is **eliminated** (the edge writes with its `AccessKey`); KotlinCrypto no longer needed on-device |
| Engine ledger | **SQLDelight** (2.3.2) | per-key upload memory; the extension is the sole **writer** AND owns reconciliation; the app is a **read-only** reader (aggregates peek for status, §2.4) and constructs no `LedgerWriter`; status is ledger-sourced, notify-driven; `resetTo` seeds on the extension's marker-gated (re)join |
| Persistence | **okio + kotlinx.serialization** | tiny App-Group store: change token, start date, eventId; event config in shared **Keychain** (via `:capability:config`) |
| Config | **BuildKonfig** | edge host (`BackgroundUploadURLBase`); **no secrets** (storage `AccessKey` lives on the edge). Event config is runtime (deeplink) |
| Backend | **Deno + Hono + bunny Edge Scripting** | the `backend/` streaming proxy upload endpoint (`@bunny.net/edgescript-sdk` + Hono routing); native Storage `PUT` + `AccessKey`; deployed via GitHub Action |
| Deeplink | **`:capability:config`** | `snapsync://` → EventConfig provisioning (was S3Config) |
| Logging | **Kermit** | multiplatform |
| iOS integration | **direct framework integration** | `embedAndSignAppleFrameworkForXcode`; framework in app + extension |

---

## 8. Open / deferred decisions

**Resolved (the 2026-06-22 scope pivot):**
- **Contributor-only, event-scoped.** App joins an externally-created event by scanning a `snapsync://`
  QR with the native Camera; uploads photos with **capture date ≥ event start** to
  `<eventId>/<assetId>-<role>.<ext>` (originals only) plus a per-asset `<eventId>/<assetId>.manifest.json`
  (`immutable-asset-manifests`). No in-app create/QR/viewing.
- **Single event at a time**; **the extension reconciles** (marker-gated: seed already-stored photos
  before uploading, clear cursor); a **switch** resets the extension's private ledger; same-event
  re-join is a no-op. The app runs no join and holds no ledger. Multi-event **deferred**.
- **Leave is supported** (`leave-event`), local-only: `disable producer → ConfigStore.clear()` — only,
  surfaced as a joined-layer button + confirm dialog. It touches no ledger/cursor/marker (the extension
  resets its own on the next marker mismatch). Already-uploaded objects stay in storage (no remote
  delete); re-scanning the QR re-joins and reconciles them back.
- **Storage = bunny.net native Storage API** for uploads/registry/listings; **device holds no
  credential**; an external **edge proxy endpoint** streams bytes into the bucket **by event id only**
  (event id = the capability). Edge host is BuildKonfig; **no baked secrets** (the storage `AccessKey`
  lives only on the edge). *(2026-06-22 proxy pivot — replaces the earlier mint/presigned model and
  eliminates the UNSIGNED-PAYLOAD risk.)* **Downloads pivot to S3 (2026-07-02, `add-s3-presigned-downloads`):**
  on the S3-enabled `snap-sync-dev` zone, listings return **presigned S3 GET URLs** the device fetches
  directly from bunny (native + S3 share one object namespace); uploads/listings stay native.
- **`localIdentifier` keys** (no `PHCloudIdentifier` resolution); **flat `<eventId>/` namespace, no
  device level** (`flatten-event-namespace`); **no custom metadata headers** (unsupported by bunny
  native API) — downstream reconstruction uses the key path + in-file EXIF; v1's §3.5 signed-header
  scheme **removed**.
- **Originals only, immutable** (`immutable-asset-manifests`): each asset's original primary + a Live
  Photo's paired video, keyed by generic role; edits/adjustments/RAW alternate dropped, set fixed at
  capture. A per-asset manifest declares the resource set; the list endpoint computes completeness at
  read time.
- **`active` = permission ∧ joined**; "not joined" is the **setup-gate no-event state**, not a new
  `SyncState`. Status screen shows **progress only**.
- **`:capability:s3` SigV4 presigner retires**; signing is **eliminated** (the edge writes with its
  `AccessKey`); Ktor is **no longer load-bearing** for upload (the provider builds URLs locally; the
  edge is Deno); the SigV4 golden suite retires with it.
- **Ack-path key recovery resolved:** the labeled edge URL carries the key in the path, recovered from
  `job.destination.URL.path` — the field the shipped extension already proves survives a job re-fetch.

**Carried forward unchanged (architecture):** the ledgered `SyncEngine` + platform seam (§2.2),
snapshot status seam (§2.3), suspended-first classification with no FAILED state (§2.4), the
design-system rules (§5), the dual-UI desktop harness (§5.1), and the three testing rules (§6).

**Still open — verify on device / Xcode (iOS 27 + bunny native, the proxy's open assumptions):**
- Does the system send an **`OPTIONS` resumable-upload preflight** to the edge, and does it fall back
  to a plain `PUT`? (v1 verified no preflight against raw AWS S3; re-verify for our origin.) The
  endpoint answers OPTIONS non-resumable; **resumable uploads are the deferred fix** if a large
  paired-video exceeds the budget.
- System uploader accepts the **edge destination URL** + `BackgroundUploadURLBase` = the edge host;
  and which **`2xx`** it treats as success.
- **Largest Live-Photo paired-video** completes within the edge **30 s CPU** budget (expected: yes —
  pass-through is I/O-bound) and within any **undocumented wall-clock/idle timeout**.
- **Edge reachability + latency from the extension** (one PUT per `Work` resource).
- Required **entitlements**; exact `PHAssetResource ↔ job ↔ key` association; `creationRequestForJob`
  semantics (incl. iCloud-offloaded resources).
- **App Group + shared Keychain** wiring (event config + change token + bookkeeping shared by app and
  extension; the **manifest PENDING/DONE files** shared both ways — extension writes PENDING, app
  writes DONE + prunes; framework in both targets); App-Group **file-protection class** for
  locked-device extension writes. The **ledger DB is extension-private** (not shared/read by the app).
- **App-Group file sharing cross-process** (the extension's PENDING manifest writes ↔ the app's DONE
  writes / prunes). The ledger is single-process (extension-only), so no cross-process DB access.
- **Swift ↔ `Flow` interop** for collecting `handle()` from the extension (SKIE or a thin wrapper).
- **Native-Camera + custom-scheme** reliability (and the not-installed dead-end) — accepted, revisit if
  it bites; a **Universal Link** is the upgrade path.

**Remaining non-trivial design questions (deferred, not blocking):**
- **Leave / deprovision-to-idle** (the opening brief's "stop uploading" — intentionally deferred).
- **Multi-event** membership (fan-out a photo to N events; the engine/ledger become event-multiplexed).
- **In-app viewing** (would reintroduce a read/download path the current design excludes).
- **Backend hardening** (rate-limiting the open create/upload, overwrite rejection, abuse protection —
  App Attest is the middle-ground option if needed).
