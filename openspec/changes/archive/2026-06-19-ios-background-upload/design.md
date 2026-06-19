## Context

`docs/design.md` §3.3 specifies a full PhotoKit background-upload extension (discover → engine → S3 presigned upload → retry/complete adjudication). Nothing produces ledger entries yet, so the live iOS status UI can only ever show `Loading → NeverSynced`. This change implements the **vertical skeleton** of §3.3 phase 3: real on-device discovery driving the real engine and App-Group ledger, but with a **dummy** upload destination and **no real upload** — the smallest slice that proves the extension runs, writes cross-process, and lights up the UI.

Key facts established during exploration (all verified against the installed Kotlin/Native 2.4.0 Photos klib and Apple's docs):

- The iOS 26.1 protocol is `PHBackgroundResourceUploadExtension : AppExtension` (ExtensionKit); its entry points are `process() -> PHBackgroundResourceUploadProcessingResult` and `notifyTermination()`. iOS 27 renamed/replaced it with the async `PHBackgroundResourceUploadJobExtension` (`processJobs()`), which can't run on today's GM devices.
- Kotlin/Native 2.4.0 binds the **full** job surface — `PHAssetResourceUploadJob`, `PHAssetResourceUploadJobChangeRequest` (`acknowledge`, `retryWithDestination`, `creationRequestForJobWithDestination`, `placeholderForCreatedAssetResourceUploadJob`), `setUploadJobExtensionEnabled`, `fetchPersistentChangesSinceToken`, `currentChangeToken`, `PHPersistentChangeToken`. The **only** symbol absent is the `AppExtension` protocol itself (Swift-only).
- The feature needs **no Apple-approved entitlement** — App Groups is the only capability; authorization is user photo-access grant + `setUploadJobExtensionEnabled(true)`.

## Goals / Non-Goals

**Goals:**
- A real iOS 26.1 background-upload extension that runs on-device, discovers photos, and **emits/logs dummy upload destinations**.
- Drive the **real** shared `SyncEngine` and write the **real** App-Group ledger (`REQUESTED`), so the existing live-status UI reflects discovery cross-process.
- Keep the deprecated-API bet thin and reversible (logic in Kotlin; Swift shell ~15 lines).
- Codify the manual portal/signing setup so the existing cloud-managed pipeline keeps working.

**Non-Goals:**
- Real HTTP upload / `S3UploadRequestProvider` wiring; the OPTIONS-preflight question (`docs/design.md` top risk).
- Retry/backoff adjudication; `COMPLETED` recording; `UploadFailed`/`UploadCompleted` round-trips.
- iCloud-offloaded (non-local) resource handling; limited photo-access support; any asset-selection UI.
- Migrating to the iOS 27 async API (deferred until iOS 27 is stable).

## Decisions

**D1 — Skeleton of §3.3 phase 3, dummy provider as the only delta.** `DummyUploadRequestProvider` (mints/logs `https://dummy.invalid/<key>`) is the swap-in twin of `S3UploadRequestProvider`; everything else is the real engine/ledger path. *Alternative — log-only, bypass the engine:* rejected; it would not exercise the dedup/decision seam that is the whole point, and would re-implement skip-on-proof at the PhotoKit edge.

**D2 — Discovery inside `process()`, not the foreground app.** Matches `docs/design.md` §3.3 (the extension owns discovery). The app's only producer-side act is `setUploadJobExtensionEnabled(true)` on grant. *Alternative — foreground app discovery:* more observable during bring-up but diverges from the design and the eventual background reality.

**D3 — Honest `REQUESTED`-only ledger.** With dummy destinations nothing truly uploads, so `COMPLETED` is never recorded; success = extension installed, photos enumerated, dummy URLs logged, `pending` climbing. *Alternative — fake `COMPLETED` at creation:* a nicer screenshot but a lie that contradicts the engine's skip-on-proof contract and would mask real-upload wiring later.

**D4 — Lean separate module + framework.** `:app:ios:photokit-extension` depends only on `:domain:engine` (no Compose), giving the extension a small binary (extensions have tight budgets). The app does **not** link this module (it only needs the reader + the enable call), so the two static frameworks never both pull `:domain:engine` into one binary — no duplicate-symbol problem. *Alternative — reuse `SnapSyncKit`:* drags Compose/UI into the extension and risks duplicate symbols in the app process.

**D5 — Kotlin owns everything except the `@main` shell.** Verified by klib inspection. *Alternative — Swift extracts primitives and Kotlin only runs the engine:* unnecessary now that K/N binds the whole job API; would scatter logic across the bridge.

**D6 — App-Group ledger, extension = writer, app = reader; Darwin dings.** `iosLedgerBackend()` resolves the `group.app.snapsync` container path (single naming site), WAL mode. Cross-process freshness via a Darwin notification posted on `put` and merged into `changes` — exactly what `Ledger.kt:69-71` anticipated. Read-only enforced structurally by the existing `LedgerReader`/`LedgerWriter` type split, not a read-only connection. *Alternative — foreground re-read only:* simpler but the user chose live cross-process dings.

**D7 — Minimal in-process cursor; no deferred/residue persistence this slice.** The full design (design.md §3.2) is a lossy-tolerant App-Group `{lastToken, residueIds, deferredIds}` store. This slice deliberately ships the floor: the change token is held **in-process only** (cold start re-enumerates — the ledger makes that harmless, like routine token expiry), and `identifierNotFound` assets are **skipped**, not deferred. *Rationale:* the deferred set is a timeliness optimization, not a correctness requirement — routine full re-enumeration + ledger idempotency already guarantee an unresolved asset is eventually backed up; the only cost is latency for the narrow class of just-created/unreconciled assets, irrelevant when nothing really uploads. `deferredIds` + per-record token persistence return with the real-upload slice (ideally after a device test settles whether iCloud reconciliation re-emits a change, which could make `deferredIds` redundant). `localIdentifier` is used only as the transient discovery/resolution handle the change feed forces — it never enters a key.

**D8 — iOS 26.1 deprecated protocol now; migrate to 27 later.** The slice's whole point is running on-device today; iOS 27 hardware/runners don't exist yet. The Kotlin-heavy split confines the future migration to the Swift shell + deployment target.

**D9 — Ports-and-adapters split so the logic is tested on the simulator.** The extension module is divided: `commonMain` holds the *testable core* — `UploadCycle` (the discover→decide→create→drain orchestration), the `UploadJobPlatform` port, and the pure `UploadKeys` (`<cloudId>-<kind>.<ext>` layout) — while `iosMain`'s `IosUploadJobPlatform` holds *only* the raw PhotoKit calls. `commonTest` exercises `UploadCycle` (with a fake platform + real engine + in-memory ledger) and `UploadKeys` on the simulator via `iosSimulatorArm64Test`; a tiny `iosTest` `PhotoKitSmokeTest` confirms the general PhotoKit enumeration surface is callable on the sim. *Rationale (research-backed):* the simulator runs general PhotoKit but **not** the iCloud-dependent cloud-identifier mapping nor the device-only background-upload-job subsystem, so those stay behind the port in the adapter (device-verified, kept as dumb as possible — every line there is untested). This is the standard hexagonal boundary: push logic into the tested core, keep the framework adapter minimal. *Alternative — leave discovery+decision tangled with PhotoKit (the first cut):* nothing is unit-testable and the dedup/decision logic can only be verified on a device.

## Risks / Trade-offs

- **Bootstrap: does `process()` fire with an empty job queue?** → If the system only schedules `process()` when jobs already exist, discovery-in-`process()` can't self-start. Mitigation: app-side **ignition** — on first enable, create one seed job (or a minimal enumeration) so the OS starts calling `process()`. This is the #1 spike and is *not* in `docs/design.md`'s open list yet.
- **26.1 entry-point shape unconfirmed** (`process()` vs a completion-handler variant; exact `AppExtension` declaration). → Spike against the SDK before writing the shell.
- **`https://dummy.invalid` may fail `BackgroundUploadURLBase` host validation.** → Spike; fall back to a syntactically-real but unrouted host if rejected.
- **Cloud-identifier determinism** (`docs/design.md` §8a) — a provisional `PHCloudIdentifier` that changes after reconciliation would corrupt keys. → For the dummy slice the cost is only a duplicate `REQUESTED`; defer the real verification but don't depend on stability yet.
- **Deprecated API removal.** → Bet is thin and reversible (D8); track iOS 27 GA.
- **Simulator can't run the extension.** → Merge gate is compile-only; runtime is on-device manual verification.

## Migration Plan

1. Land Kotlin core + Swift shell behind the new target; no behavior change to the existing app until the extension is enabled.
2. One-time manual portal setup (App Group + extension App ID + capabilities) — see `tasks.md` checklist — must precede the first signed device build or cloud signing fails.
3. Verify on a physical iOS 26.1 device: enable, observe logged dummy URLs + climbing `pending`.
4. Rollback: the extension is additive; disabling `setUploadJobExtensionEnabled(false)` and not shipping the target reverts to today's empty-ledger behavior. The `iosLedgerBackend()` path move is the only app-side change and is a one-line revert.

## Open Questions

- Bootstrap trigger for `process()` with an empty queue (drives whether the ignition kick is needed).
- Exact 26.1 `process()`/`notifyTermination()` signatures and `@main`/`AppExtension` declaration.
- Does cloud-managed signing auto-create the extension App ID, or must it pre-exist in the portal? (Assume pre-exist.)
- Does the extension target need its own `NSPhotoLibraryUsageDescription`, or inherit the host's?
- App-Group DB file-protection class for writes from a locked-device extension (`NSFileProtectionCompleteUntilFirstUserAuthentication`).
- Swift ↔ Kotlin `Flow`/suspend interop for the shell calling the core (SKIE or a thin callback wrapper).
