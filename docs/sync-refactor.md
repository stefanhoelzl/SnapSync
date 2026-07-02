# Design note — relocate upload orchestration for harness coverage (minimal refactor)

**Status:** proposal (not yet implemented). Product of a design interview + four review rounds + two
fresh-reviewer passes, 2026-07-01/02.
**Goal:** maximize platform-agnostic, JVM-runnable code so the sync orchestration is covered by
automated tests (and the desktop harness), i.e. **thin platform = minimal platform-specific code.**

> **What five review passes converged on.** The seam is **already the right shape** — the current
> `UploadJobPlatform` (fine-grained OS verbs) + `commonMain` `UploadCycle` (all adjudication) is exactly
> the "fake transducer drives the real core" design, covered by 20 `UploadCycleTest` cases against the
> real engine + real ledger. The only real problem is that `UploadCycle` lives in a module that **has no
> `jvm()` target**, so it isn't JVM/harness-reachable. So this refactor is **mostly a relocation + a few
> pre-existing bug fixes** — not a seam redesign, not a module cluster, not an `Asset` vocabulary. Those
> were explored across the review rounds and dropped as churn/premature (see §7).

---

## 1. Keep the current seam (do NOT redesign it)

The engine⇄platform seam stays as-is: the platform exposes **fine-grained OS-API verbs**
(`fetchRetryJobs`/`fetchAckJobs`/`retryJob`/`createJob`/`acknowledge`/`discoverResources`), and the
**shared `UploadCycle`** orchestrates them (drain both buckets → engine → retry-vs-recreate → create/retry →
ack; backpressure; cursor advance). This keeps the adjudication in `commonMain` (harness-covered) and the
iOS impl a thin adapter.

**Why not the "clean" narrow seam** (`postJobs`/`drainResults`-terminal/`acknowledge`): it would move the
retry/bucket/ack logic *into* the iOS-only impl (regressing harness coverage — the opposite of the goal),
and `acknowledge(keys)` is an **identity regression** vs today's by-handle ack (two OS jobs can share a key
mid-cycle → mis-ack → lost upload or 50008). The five-flow message model (gallery-changes / upload-jobs /
upload-results / download-requests / download-results) is a fine **mental map** of the seam; it is **not**
the literal contract.

Download is already this shape (`DownloadController` in `:capability:download`, `commonMain`).

---

## 2. The refactor — one relocation + one narrow extraction

**Move B (the real win) — relocate the upload orchestration into a `jvm()`-enabled module.**
`UploadCycle`, `UploadJobPlatform`, `DiscoveryStore`, `UploadConfig` are pure `commonMain` (imports only
`app.snapsync.engine.*`) but sit in `:app:ios:photokit-extension`, which declares **only
`iosArm64`/`iosSimulatorArm64`, no `jvm()`** — so their `commonTest` doesn't run on JVM (a latent
testing-rule-1 violation) and `:app:desktop` can't reach them. **Relocate them into a new
`:capability:upload`** — symmetric with `:capability:download`/`:capability:rejoin` (same templated
`build.gradle.kts` with `jvm()`+`iosArm64`+`iosSimulatorArm64`, depending only on `:domain:engine`). This
**auto-unlocks JVM regression coverage** of the whole upload orchestration — the goal, delivered — and lets
the desktop harness reach it. No new cross-module edges, no cycle (the download-store/gallery/rejoin/etc.
wiring stays in the iosMain composition root `UploadExtensionRoot`). *Do not* fold into `:domain:engine` —
that would pollute its "sync core + SQL ledger, no platform deps" contract with job-lifecycle vocabulary.

**Move A (rescoped — smaller than first written) — a fakeable raw-Asset walk seam.**
Correction after review: the fan-out **policy** (originals filter, `type→role`, filename) is **already
agnostic and JVM+simulator-tested** in `:domain:gallery/UploadKeys.kt` (`resourceRole`, `uploadKey`) — it is
*not* trapped in the iOS enumerator. What's actually iOS is the **PhotoKit walk** + raw-fact extraction.
So Move A's real value is narrow: give the enumerator a **fakeable `RawAsset` seam** so `discoverChanges`
is harness-scriptable.
```kotlin
class RawAsset(assetId, creationDate, rawResources: List<RawResource>)
class RawResource(type: ResourceType /*raw PHAssetResourceType value*/, contentTypeUti: String,
                  mimeContentType: String /*resolved iOS-side; see §5*/, originalFilename: String, handle: Any /*opaque*/)
```
The shared side maps `RawResource → UploadResource` via the *existing* `UploadKeys` functions. The iOS
enumerator shrinks to a decision-free walk emitting `RawAsset`s. **`UTType.preferredMIMEType` (UTI→MIME)
stays iOS-only** (Apple's UTI table — reimplementing it is a correctness risk), so `mimeContentType` is a
raw fact carried out of the walk, not computed in `commonMain`.

**Thin-platform cleanup:** relocating the orchestration already removes the hard-rule tension (real,
JVM-testable logic currently in the "wiring-only" `:app:ios:photokit-extension`). The accurate framing is
"**not harness-reachable**," not "untested" — the tests exist, they just don't run on JVM.

---

## 3. What ends up where

- **`commonMain` (agnostic, JVM-covered):** the engine; `UploadCycle` + `UploadJobPlatform` (interface) +
  `DiscoveryStore` (→ `:capability:upload`); `DownloadController` (already `:capability:download`);
  reconcile (already `:capability:rejoin`, `jvm()`); fan-out policy (already `:domain:gallery`); the
  `RawAsset` seam types.
- **`iosMain` (decision-free adapters, faked in the harness):** `IosUploadJobPlatform` (the OS job-lifecycle
  adapter — irreducible but decision-free), the PhotoKit walk, `URLSession`, PhotoKit import, SQLite driver,
  Keychain, deeplink parse, `setUploadJobExtensionEnabled`.
- **`:app:ios*` (wiring-only):** composition roots.

---

## 4. Coverage: two distinct payoffs (don't conflate them)

- **Automated regression coverage (load-bearing, comes free from Move B):** the moment `UploadCycle` lives
  in a `jvm()` module, its `commonTest` (`FakePlatform` + real engine + real ledger) runs on JVM in CI —
  that is the durable, per-rule-1 coverage the goal is about. It needs **no UI work.**
- **Interactive/exploratory harness (nice-to-have):** extending `:app:desktop`'s control panel to drive the
  real engine + cycle against non-test fakes gives a human/agent a clickable sync driver (per
  `design.md §5.1`'s existing intent). It is **test equipment (no tests)**, a substantial new build-out, and
  **not required** to realize the goal. Sequence it after, and only if wanted.
- **Harness blind spot (honest):** single-process, engine-live — it **cannot** cover the iOS cross-process
  rehydration (drain lands in a later process than post). That stays device-verified.

---

## 5. What stays iOS-only (irreducible)

`IosUploadJobPlatform` — the OS upload-job adapter (two-bucket fetch, `retryWithDestination` vs recreate,
ack-all-or-50008, `resource==nil` for succeeded, change-token archiving). Decision-free but real adapter
code, not harness-covered (faked). The PhotoKit walk. `URLSession`. PhotoKit import. **`UTType`→MIME
mapping.** SQLite driver, Keychain, deeplink, the OS extension toggle.

---

## 6. Sequencing (independent PRs, tests first)

1. **Pre-tasks (§7)** — pre-existing correctness fixes, independent of the moves.
2. **Move B** — relocate to `:capability:upload` (behavior-preserving; its tests now run on JVM).
3. **Move A** — the `RawAsset` walk seam (behavior-preserving); needs a lightweight OpenSpec delta (new seam
   contract) + a `:domain:gallery` note.
4. **(optional)** grow the desktop harness into an interactive driver.

Housekeeping: refresh the **already-stale `CLAUDE.md` module table** (it omits several existing modules) as
part of this.

---

## 7. Pre-tasks — pre-existing correctness fixes (do first)

1. **Robust `clearRequested` + re-enable ordering** *(verified live bug)*. `SnapSyncRoot.disableExtension()`
   does `scope.launch { ledgerBackend.clearRequested() }` on `Dispatchers.Main` — a synchronous SQLite write
   on the main thread (hang), fire-and-forget, and it races the immediate `setUploadExtensionEnabled(true)`
   → the re-enabled extension's fresh `REQUESTED` rows get deleted. Fix: **awaited, bounded-retry, off-main
   (`withContext(Dispatchers.Default)` — NOT `Dispatchers.IO`, which doesn't exist on Native), completing
   before re-enable.** `busyTimeout` is already ~5s (SQLiter default) — don't "set" it.
2. **Fix `reconstruct`** *(verified latent bug)*. `entry?.assetId ?: ""` writes a phantom `assetId=""`
   `COMPLETED` row when the ledger row was pruned. Derive `assetId` from the key (share the existing
   `assetIdFromUploadKey` parser) **and** gate the record on `key != null`.
3. **Bound reconcile's network `LIST` only.** `withTimeout` the device-listing `LIST` (defer-on-timeout, as
   the 12s manifest guard does). Keep `resetTo` a single atomic transaction — the insert is ~seconds even at
   ~100k rows; only the network call needs bounding.
4. **Reconcile keeps the simple `resetTo(listing)`** (no empty-listing guard — item settled: accept the rare
   re-upload; `502` already defers). *Optionally* keep the cheap `defer iff empty && ledger has COMPLETED`
   guard against a same-session-switch transient (see must-verify). Verify Keychain `device-identity`
   persistence separately.
5. **Enforce `SuppressionSource` narrowing.** Extension links a read-only `SuppressionSource` factory, not
   the full `DownloadStore`.
6. **Cross-module contract tests** (need a named home — `:test:integration` is not built yet; pick an
   interim module): the `assetId ↔ createdLocalId` `'/'→'_'` normalization **and** the `key → assetId`
   parse round-trip (both now load-bearing at the record/suppression paths).
7. **Delete dead code** (`EventFilesSource`/`HttpEventFilesSource` + stale comments).
8. **Doc-accuracy fixes.** `design.md §2.2` (stranded `REQUESTED` isn't re-enum-rescued), §2.4 ("sole
   writer" is imprecise — the app writes via `clearRequested`); suppression predicate is `createdLocalId IS
   NOT NULL`; `Role` naming (`primary`/`motion` vs code's `live`); the `:app:ios:photokit-extension` module
   is "not harness-reachable," not "untested." Refresh the `CLAUDE.md` module table.

*(Dropped after review: the marker→ledger-table migration — the marker is `NSUserDefaults`, already written
last, so the crash-fence holds; its only benefit (destructive-migration desync) is moot under the
accept-re-upload decision.)*

**Must-verify-on-device:** pre-launch extension invocation; `clearRequested` off-main completes before
re-enable under cross-process WAL contention; `busyTimeout` sufficiency under Class-C cold-boot; reconcile
completes on a large library (~20–50k) with the `LIST` bounded; **bunny Storage `LIST` is read-your-writes
consistent after recent PUTs** (the one assumption behind dropping the empty-guard); Keychain
`device-identity` survives reinstall; extension leanness + two static frameworks still build after Move B.

---

## 8. Review history (condensed)

Six review passes (four rounds + two fresh). Ideas explored and **dropped**: temporal single-writer +
drop-the-marker + app-side eager reconcile (round 1–2, unsafe); a four-module `:sync:*` cluster + `Asset`
vocabulary + two-engine split (round 3–4 + fresh, premature/churn — the modules already have that shape,
`SyncEngine` is already upload-only, fan-out is already agnostic); a narrow message seam that hides the iOS
job lifecycle (final fresh pass — regresses harness coverage and the ack identity model). What survived, and
is well-evidenced: **keep the current wide seam; relocate `UploadCycle` to a `jvm()`-enabled
`:capability:upload` (the coverage win); a narrow `RawAsset` walk seam; and the pre-existing correctness
fixes in §7.** Deferred until a second platform (Android) exists to design from: the cluster + `Asset`
vocabulary.

---

## 9. Appendix A — the seam contract, at a glance

Two sides. **Engine** (shared · `commonMain` · JVM/harness-covered): decisions, the SQL ledger + all
bookkeeping, orchestration (`UploadCycle`/`DownloadController`), fan-out + key layout, reconcile +
echo-suppression. **Platform** (iOS · `iosMain` · decision-free · faked in the harness): the PhotoKit
walk, the OS upload-job API, background `URLSession`, PhotoKit import, the SQLite driver, Keychain, the
deeplink parse, and the OS extension toggle.

Direction is by data flow: **OUT** = the engine commands the platform to act; **IN** = the platform
reports what PhotoKit / the OS did. Everything crossing (twelve data messages + one effect):

| Dir | Message | What crosses |
|---|---|---|
| **Upload** | *(interface `UploadJobPlatform`)* | |
| IN | `discoverResources(sinceToken) → Discovery` | changed/removed assets + next cursor · *Move A → `RawAsset`s; fan-out moves engine-side* |
| IN | `fetchRetryJobs() → List<PlatformUploadJob>` | first-failure jobs (one free OS retry) |
| IN | `fetchAckJobs() → List<PlatformUploadJob>` | terminal jobs (succeeded / retry-spent) |
| OUT | `createJob(request, resource) → CreateResult` | post a job → `CREATED` / `LIMIT_EXCEEDED` / `FAILED` |
| OUT | `retryJob(job, request)` | re-point the OS's single free retry |
| OUT | `acknowledge(job)` | confirm terminal (after record), free the slot — ack all or 50008 |
| **Download** | *(DownloadController seams)* | |
| OUT | `enqueue(downloads)` | start background byte transfers |
| IN | `onStaged(ref, key, path)` | a resource's bytes landed in staging |
| OUT | `import(ref, staged, creationDate) → localId` | create one PhotoKit asset; `localId` is the suppression handle |
| OUT | `cancelAll()` | cancel in-flight transfers (leave/switch) |
| **Reconcile** | *(HTTP listing sources)* | |
| IN | `listDeviceFiles(deviceId) → List<filename>` | already-stored files → seed the ledger `COMPLETED` |
| IN | `union(eventId) → List<UnionAsset>` | foreign assets to download & import |
| **Lifecycle** | | |
| OUT | `setExtensionEnabled(enabled)` | toggle the OS extension (a command, not data) |

**Opaque handles ride across uninterpreted:** `Resource.data`, `PlatformUploadJob.handle`/`.data` are
`Any` — the real `PHAssetResource` / OS job objects cross without the engine reading them, which is what
lets a fake platform stand in. **The harness is a fake right-hand side:** swap a fake implementing these
methods and the whole engine column runs on the JVM. **Blind spot:** on device `fetch…` results arrive in
a *later* process than `createJob`, so the engine rehydrates from the ledger; the single-process harness
keeps the engine live, so that cross-process path stays device-verified.

## 10. Appendix B — one upload cycle (sequence)

`UploadCycle.run()` across a single OS-scheduled `process()` invocation — three phases, two invariants,
two exits. (Timing is OS-owned; you cannot force *when* `process()` runs.)

1. **Adjudicate first failures.** `fetchRetryJobs` ◀ → engine `UploadFailed → Retry` (records `FAILED`,
   rebuilds the request locally against the stable edge URL) → `retryJob` ▶ → engine `UploadStarted`
   records `REQUESTED`. — **Invariant: write-after-act** — `REQUESTED` is recorded only *after* the OS
   accepts the job, so a crash yields a bounded duplicate, never a stranded photo.
2. **Acknowledge terminals.** `fetchAckJobs` ◀, then per job: *succeeded* → `UploadCompleted` records
   `COMPLETED` → `acknowledge` ▶; *retry-spent* → records `FAILED` → `createJob` recreates → `REQUESTED`
   → `acknowledge`; *already `COMPLETED`* → just `acknowledge`. — **Invariant: record-before-ack** — a
   crash between record and ack re-presents the job and the idempotent ledger absorbs it; **ack every
   presented job or the OS errors 50008.** If the job cap is hit → return `PROCESSING` (cursor held).
3. **Discover & create.** `discoverResources(cursor)` ◀ → drop suppressed (echo) + prune removed → per
   resource engine `ResourceChanged → Upload | AlreadyUploaded` (ledger-gated skip of `COMPLETED`/
   `REQUESTED`) → `Upload` → `createJob` ▶: `CREATED` records `REQUESTED` · `LIMIT_EXCEEDED` stops →
   `PROCESSING` · `FAILED` skips (retry next discovery). On a full enumeration → `retainAssets` prunes
   rows for absent assets; then write the `device.json` manifest and `saveToken` (advance the cursor).

**Exits:** `COMPLETED` (fully drained, cursor advanced) or `PROCESSING` (backpressure/cap, cursor **held**,
OS re-invokes). **The cursor advances only on a fully-drained cycle**, so a truncated cycle re-derives the
same work and the `REQUESTED`/`COMPLETED` skip keeps it idempotent. Every step here is engine-side Kotlin;
only the six seam calls touch the platform — a fake `UploadJobPlatform` runs the whole sequence on the JVM
(the visual argument for keeping `UploadCycle` on the engine side, i.e. Move B).
