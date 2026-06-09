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
- A **local desktop (JVM) test app** with a controllable fake backend + side-by-side control panel.

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
- **Platform-independent backend** — domain models, sync orchestration, and the MVI presentation
  layer. Knows nothing about the platform.
- **Platform backend** — gallery/discovery and the upload-job system, behind **two injected interfaces**.

Implementations are selected by **dependency injection in the app modules** (manual composition
root), *not* `expect`/`actual`. Rationale: the JVM target needs **multiple** impls of the same
seam (in-memory fake for tests, the controllable fake for the desktop app), which `expect`/`actual`
(one impl per compile target) cannot express; a plain `interface` + DI can.

A large share of the orchestration runs **inside the iOS extension's `processJobs()`**, which calls
the same shared `:domain:sync` logic. The desktop app drives that same logic with fakes (simulating
`processJobs()` invocations), so the orchestration is exercised off-device.

### 2.1 Module graph

```
:domain:model          models (AssetRef, ObjectKey, UploadJob, SyncStatus, SyncError…). no deps.
:domain:sync           → :domain:model + the capability interfaces. The upload-cycle orchestration
                         (retry → acknowledge → create-new-jobs) called from the iOS extension and
                         the desktop fake alike.
:domain:presentation   → :domain:sync. Orbit MVI container(s) + UiState. COMPOSE-FREE.
:domain:ui             → :domain:presentation. Compose screens via the design-system abstraction.

:capability:gallery    → :domain:model. GalleryService iface (permission + change-feed discovery)
                         | iosMain: PhotoKit (PHPhotoLibrary, PHPersistentChangeToken)
                         | jvmMain: controllable fake  | (androidMain later)
:capability:uploader   → :domain:model. UploadJobService iface (create/fetch/retry/ack/cancel jobs)
                         | iosMain: PHAssetResourceUploadJob APIs  | jvmMain: controllable fake
:capability:s3         → :domain:model. pure common: SigV4 presigned-PUT URL generation (+ optional
                         ListObjectsV2 backstop) over Ktor. NOTE: the actual PUT is performed by iOS,
                         not by us — we only mint the destination URL.
:capability:store      → :domain:model. pure common (okio): tiny App-Group-backed persistence
                         (the PHPersistentChangeToken bytes, last-sync timestamp, bookkeeping).

:app:ios               wires impls + Darwin Ktor engine + framework export → iosApp/ (Xcode)
:app:desktop           wires fake impls + JVM Ktor engine + side-by-side control panel

iosApp/                Xcode project (not Gradle): the app target (Swift host + Info.plist) AND a
                       PHBackgroundResourceUploadJobExtension target (Generic Extension; extension
                       point com.apple.photos.background-upload; BackgroundUploadURLBase = bucket
                       host). The :app:ios framework is embedded in BOTH targets (the extension needs
                       presign/SigV4 + config). App and extension share an App Group container.
```

Dependency flow: `:domain:model ← everything`; capabilities depend only on `:domain:model`;
`:domain:sync` depends on the capability **interfaces**; app modules wire the concrete impls.
Every boundary is **compiler-enforced** (e.g. `:domain:ui` cannot see the S3 client), and the
backend swap is **structural** (`:app:ios` wires PhotoKit impls; `:app:desktop` wires fakes).

The **capability modules follow the okio model**: organized by *what they do*, with per-platform
impls hidden inside as source sets. "Has platform code or not" is an internal detail (`:capability:s3`
and `:capability:store` are pure-common).

### 2.2 Platform seam (two interfaces)

- **`GalleryService`** — `requestAccess()`/`authorizationStatus()`, and **change-feed discovery**:
  `changedAssets(sinceToken): (assets, newToken)` (iOS: `PHPhotoLibrary.fetchPersistentChanges`).
  No `exportOriginal` — the system reads the bytes during upload, not us.
- **`UploadJobService`** — wraps the upload-job system: `createJob(asset, destinationRequest)`,
  `fetchJobs(action)`, `retry(job, destination?)`, `acknowledge(job)`, `cancel(job)`, plus
  `jobLimit` and per-job `state`/`error`/`responseHeaders`. iOS maps to `PHAssetResourceUploadJob*`;
  desktop is a controllable fake.

The controllable **fakes live in each capability's `commonMain`** (so both `:domain:sync`'s
`commonTest` and `:app:desktop` reuse them; unused on iOS, DCE-stripped).

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

- **`photos/<sanitized assetLocalId>/<resourceType>.<ext>`** (localId slashes → `_`; the resource
  type disambiguates resources that share a filename), `Content-Type` from the resource's UTI.
- **Edit-handling dissolves:** the original resource is immutable (no re-upload churn); an edit just
  **adds** `.fullSizePhoto` + `.adjustmentData` resources, which surface via the change feed as *new*
  jobs under *new* keys. Nothing is overwritten or lost.
- **Reconstruction metadata rides the upload as signed `x-amz-meta-*` headers** on each resource's
  destination request — so it's **as reliable as the bytes, no app turn required** (§3.5): resource-level
  fields (`original-filename`, `resource-type`, `uti`) plus **duplicated asset-level fields** (`asset-id`,
  `created`, `modified`, `location`, `favorite`, `mediaSubtypes`, `pixels`, `duration`). The
  asset↔resources **relationship is the shared `photos/<localId>/` prefix**. Sign `host` +
  `content-type` + the `x-amz-meta-*` headers; values URL-encoded/base64 (ASCII, ~2 KB cap — fits
  easily). **No sidecar; albums out of scope.**
- Trade-offs (accepted for v1): **no content dedup**, and **re-upload after a device restore**
  (`localIdentifier` changes). PUT-by-key is idempotent, so re-uploads are just bandwidth.

### 3.2 Discovery & state (PhotoKit-native)

- **Discovery** is the `PHPersistentChangeToken`: `fetchPersistentChanges(since:)` yields new/changed
  assets incrementally. The token is persisted in `:capability:store` (App Group) between runs.
- **State / source of truth** is the **job system itself**: job states + `acknowledge()` (which frees
  an inflight `jobLimit`) + the App-Group processed-set. The app never `LIST`s the bucket
  (`PutObject`-only); restore-side `LIST` is a separate admin path (§4).

### 3.3 Flow

**App (foreground):** request `.readWrite` photo authorization → `setUploadJobExtensionEnabled(true)`
→ show status (job states + App-Group progress). **The app uploads nothing itself** — every upload
(resource bytes + their metadata headers) goes through the extension's jobs. (Disable the extension if
the user ever turns backup off.)

**Extension — `processJobs()` (system-invoked, calls shared `:domain:sync.runUploadCycle()`):** the
system downloads each resource (incl. from iCloud) and performs `job.destination` with the **resource
bytes as the request body**. We only manage the queue, in three phases:
1. **Retry failed** — `fetchJobs(action: .retry)`; inspect `job.error`. ⚠️ **One retry per job only**
   (`retry` requires failed + unacknowledged + *not previously retried*): transient & not-yet-retried
   → `retry(destination: <fresh presigned URL>)`; permanent **or already-retried** → `acknowledge()`
   (give up; record failure; optionally re-create a fresh job on a later cycle).
2. **Acknowledge completed** — `fetchJobs(action: .acknowledge)`; read `job.responseHeaderFields`
   (record the S3 **ETag** for verification), map back via `PHAssetResource.assetResource(forUploadJob:)`
   (note: `job.resource` is deprecated), mark processed, `acknowledge()` to free a slot.
3. **Create new jobs** — discovery: initial run enumerates the whole library (`PHAsset.fetchAssets`);
   steady state uses `fetchPersistentChanges(since: token)` deltas. For each not-yet-processed
   `PHAssetResource`: derive its per-resource key → mint a **presigned S3 PUT URL** → `URLRequest`
   (PUT + `Content-Type` + signed `x-amz-meta-*` metadata) → `creationRequestForJob(destination:resource:)` in `performChanges {}`,
   tracking `placeholderForCreatedAssetResourceUploadJob`. On `limitExceeded` stop and return
   `.processing`. Persist the new change token + processed-set in the App Group.
4. Return `.completed` (drained → system monitors) / `.processing` (call me again) / `.failure`.
   `willTerminate()` sets a cancellation flag so `processJobs()` bails cleanly and resumes next time.

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
- **Relationship is implicit:** all objects under `photos/<localId>/` are one asset. Restore (admin
  creds) does `LIST photos/<localId>/` → that's the complete resource set → reads asset-level meta from
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
- **Design-system abstraction** (in-house `App*` components): screens are written against it; the
  **initial skin is Material 3** (zero dependency risk). A Cupertino skin (the
  [slanos/schott compose-cupertino fork](https://github.com/schott12521/compose-cupertino), or
  hand-rolled) can be added **later without touching screens**. (All Cupertino libs are ~10 months
  stale; the abstraction contains that risk.)
- **v1 screens: minimal** — a permission gate (inline) + a single **status screen** (idle / uploading
  X of N / last-sync time / "back up now" / error states; an enable-backup toggle). No nav.
- **State: MVI via Orbit** in `:domain:presentation` (Compose-free). The `:presentation → :ui`
  contract is `StateFlow<UiState>` + actions, so the state model can evolve (MVIKotlin, or Decompose
  navigation — state-lib-agnostic) without touching the UI.
- **Errors: sealed domain errors → `UiState`**, exceptions converted at capability boundaries, logged
  via **Kermit**. Errors-as-reduced-state lets the control panel force any failure state.

### 5.1 Desktop test harness (dual UI)

`:app:desktop` wires **controllable in-memory fakes** (set the change-feed assets, force job
success/failure/retry, flip permission, simulate `jobLimit`) and renders **side-by-side**: left = the
real `:domain:ui` status screen; right = a **control panel** that drives the fakes, invokes
`runUploadCycle()` on demand, and inspects/forces `UiState`. Both bind the same Orbit container.

---

## 6. Testing strategy

- **Unit (JVM)** — `:domain:sync.runUploadCycle()` orchestration (retry/ack/create across `jobLimit`,
  change-feed paging) with fakes; SigV4 presign + key derivation; Orbit reducers. The bulk of the
  logic is here, off-device.
- **Integration** — `:capability:s3` against **s3mock** (Testcontainers): mint a presigned PUT, do
  the PUT via Ktor, assert via `LIST`. ⚠️ **Accepted risk:** s3mock does **not validate signatures**,
  so SigV4 is only truly exercised by the on-device extension upload. If a first real upload fails,
  signing is the prime suspect — the quick follow-up is a Garage (SigV4-validating) Testcontainers
  test or a real-AWS smoke test. Not adding that now (per decision).
- **iOS** — the upload extension is **physical-device only** in the current iOS 27 beta (no
  simulator). Plan: **manual on-device testing** now; move the extension into **simulator XCTest/CI
  once Apple adds simulator support** (expected by GA). Gallery/app (non-extension) parts can be
  simulator-tested earlier.
- **Desktop** — manual exploration via the control panel.

---

## 7. Chosen libraries

| Concern | Choice | Notes |
|---|---|---|
| UI | Compose Multiplatform | single codebase; Material 3 behind a design-system abstraction |
| State | **Orbit MVI** (~1.3k★) | cleanest/most-modern MVI DSL; Compose-free; Decompose-able later |
| DI | **Manual composition root** | no deps, compile-safe; Koin if it grows |
| HTTP | **Ktor** (multiplatform) | SigV4 **presign only**; **not** the upload PUT (iOS does that); app never `LIST`s |
| Crypto/IO | **okio** (+ KotlinCrypto if needed) | App-Group file IO + HMAC-SHA256 for SigV4 |
| Persistence | **okio + kotlinx.serialization** | tiny App-Group store: change token, last-sync, bookkeeping |
| Config | **BuildKonfig** | typed build-time config; secrets from env/CI |
| Logging | **Kermit** (~1k★, active) | multiplatform |
| iOS integration | **direct framework integration** | `embedAndSignAppleFrameworkForXcode`; framework in app + extension |
| Test S3 | **adobe/s3mock** (Testcontainers) | logic only; doesn't validate SigV4 (accepted risk) |

---

## 8. Open / deferred decisions

**Resolved (deferred-items pass):**
- Object key = `photos/<sanitized localId>/<resourceType>.<ext>` (per-resource metadata, no content-hash/hashing/cache); `Content-Type` from resource UTI.
- Back up **all `PHAssetResource`s** per photo asset incl. Live Photo paired video → edit-handling dissolves (edits add resources, never overwrite).
- Min iOS **27.0**, `PHBackgroundResourceUploadJobExtension`; discovery via `PHPersistentChangeToken`; job system is source of truth.
- Reconstruction metadata via **signed `x-amz-meta-*` headers** on each resource (resource-level + duplicated asset-level); relationship via `photos/<localId>/` prefix; restore = `LIST` prefix. **No sidecar — the app uploads nothing.** Albums skipped (cosmetic). App IAM = `PutObject` only; restore = separate admin path (`ListBucket`+`GetObject`).
- These dissolve the old temp-file, throttling, hashing-cache, iCloud-download-for-hashing, first-sync-cost, edit-handling, periodic-trigger, sidecar, and app-side-BGTask-upload items.

**Still open — iOS 27 API verification (on device / Xcode):**
- **TOP RISK:** resumable-upload `OPTIONS` preflight vs raw S3 — does the system fall back to plain `PUT`, or is a tiny edge endpoint needed? (§3.3). Spike first.
- System uploader accepts a **query-string presigned URL** destination + `BackgroundUploadURLBase` host match.
- Required **entitlements**; exact `PHAssetResource` ↔ job ↔ key association; `creationRequestForJob` semantics.
- App Group wiring (config + change token + bookkeeping shared by app and extension; framework in both targets).
- Timing of **simulator support** for the extension (affects when iOS tests move into CI).

**Resolved — other:**
- Limited photo access: v1 **requires full `.authorized`** (the Job extension needs it); `.limited`/`.denied`/`.restricted` → "full access required" + Settings deep link. No limited-subset support.
- Signing validation: **s3mock only** (accepted risk, §6); revisit Garage/real-AWS only if a real upload fails.

**Resolved — ops:**
- CI: **none yet** — run tests locally for now; add GitHub Actions (KMP/desktop tests on a Linux
  runner) and iOS CI once iOS 27 ships and hosted runners carry Xcode 27.
- TestFlight: **manual archive + upload from Xcode** while on beta; automate with fastlane (gym+pilot)
  at GA.

**Remaining work is iOS-27-API *verification* (above), not open design decisions.**
