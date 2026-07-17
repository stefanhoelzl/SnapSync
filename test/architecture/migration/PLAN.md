# Migration plan — current graph → `module-architecture` target

**This document dies with this module** at beacon-green; it cannot outlive the migration.
**The beacon is the truth** (`verify` job — per-law distance, 111 at plan time); this plan is the
narrative: the intended order, what each step moves, and why. Each step lands as **its own
OpenSpec change**; its PR ticks its row. This plan was adversarially reviewed (two independent
passes: build-mechanics, operational reality) before landing; the review-driven amendments are
inlined and marked ⟨R⟩ where they changed the original order or claims.

## Rules of every step

- **Behavior-preserving** unless the row says otherwise (steps 11a–12 are the decided behavior
  changes, each with spec deltas and device verification).
- **Green `./gradlew build` + `compileIosMainKotlinMetadata`** at every landing; every merge
  ships to TestFlight.
- **Regenerate diagrams** in every structural PR (required `diagrams` check) — and ⟨R⟩ when a step
  creates a new top-level source root, extend `tools/diagrams` `Scan.kt`/`Zones.kt` walk lists in
  the same PR (they are hand-listed: `app/capability/domain/test` today; `adapter` joins at
  step 4, `ui` at step 9), or the diagrams silently stop seeing the new code while the freshness
  gate stays green.
- **Guard flips are part of the diff**: `KeychainContainmentTest` (step 4),
  ⟨R⟩ `StatusEngineBoundaryTest` (retired in step 5 — superseded by the zone gates; leaving it
  turns `build` red when `domain/status` empties), `LawsDigest` (any CLAUDE.md law edit), Swift
  pins (step 12), the beacon's `targetModules` (any module create/delete),
  ⟨R⟩ `appShellSources` in the root build (prune at step 4, rename at step 13a).
- ⟨R⟩ **Every CLAUDE.md command or module reference a step invalidates is updated in the same PR**
  (runbooks are agent instructions; only the laws digest has a guard).
- ⟨R⟩ **Next step branches only after `gh pr view` shows MERGED** — ship-wait's 15-min timeout
  routinely fires on heavy steps while the PR merges later; branching off a pre-merge base turns
  the next mechanical rename into hand-surgery.

## ⟨R⟩ Protocols the reviews added

- **Freeze protocol** (steps 3a, 5, 6, 9 — the tree-wide renames): before `/ship`,
  `gh pr list --state open` must be empty (all PRs, not just auto-merge ones — the queue cannot
  see a PR whose auto-merge isn't enabled yet); no new workspace branches until MERGED; parked
  branches rebase before resuming.
- **Soak rule** (after steps 4, 8, 11a, 11b, 12): the next step's PR does not open until a
  post-merge `debug.log` pull from the dev device shows one clean OS-scheduled cycle on the
  *merged* build. This is the only revert window a fix-forward pipeline has.
- **Device sessions — four, batched** (the settle-list is *input* to designs, so it goes first):
  - **A (before step 4 merges; doubles as its smoke):** settle ①–⑥ (SDK header greps, defer-queue
    log count, backup2 attempt, CUFUA error shape); then step-4 build over a joined install → one
    cycle → both `debug.log`s: same device id enrolled, no cursor reset, no re-upload, marker
    intact.
  - **B (steps 7–8):** cold + warm universal-link delivery (the `[onOpenUrl]`/process-start
    oracle), silent push, one BGTask + extension cycle, `SNAPSYNC_SEED_POLICY` lines intact.
  - **C (steps 11a+11b):** reinstall = left; update-in-place migration assertions; revert-build
    simulation (previous IPA over migrated state → config still readable via write-through).
  - **D (step 12):** poll latency, transcriber flows, lifecycle transitions, zero-resume
    prediction confirmed in production logs.
  - Step 9 needs no device: **dispatch `screenshots.yml` once** instead — it is the only exerciser
    of `forgeStatusHost` and proves the `:ui` re-homing kept the forge path alive.
- **Human eyes before `/ship`** (zero-review auto-merge is the default; these diffs are the
  exceptions the operator reads): 4 (every moved iosMain file, literal-by-literal), 5
  (`clearRequestedOffMain` re-homing), 7 (which `readGate` copy's behavior wins), 8, 11a, 11b, 12.

## The steps

| # | step | expected Δ (law: delta) | status |
|---|------|------------------------|--------|
| 0 | ⟨R⟩ pin the runtime identity + create the pending zone gates | — (guards + consolidation) | ● |
| 1 | delete dead weight | ledger −8 · edges −3 · modules −1 (measured exact; + beacon ledger-scan self-match fix, D3 of `delete-dead-weight`) | ● |
| 2 | split mixed files | mixed −6 ⟨R: 2 of 8 die in step 1⟩ (measured exact) | ● |
| 3a | `:domain` skeleton: `model/` + `ports/` (moves + package renames only) | edges −5 (→1) · modules Δ (measured exact; Δ-note: `LedgerBackend` seated in `model/` — SyncEngine→LedgerWriter chain vs the armed model-purity gate; ports/ seat at 3b/5. D3 of `domain-skeleton-model-ports`. 3 mixed files step 2 missed split here) | ● |
| 3b | port need-renames (`LedgerStore`, `PhotoLibrary`, `PhotoAccess`, …) | — | ○ |
| 4 | adapters (ext-safe / app-only / generic) + delete the two emptied `:app:ios:*` modules | modules Δ · shells Δ | ○ |
| 5 | features I: upload · membership · status · trust (+ retire `StatusEngineBoundaryTest`) | modules Δ | ○ |
| 6 | features II: download · album · creation; delete emptied modules | edges −1 (→0) · modules Δ | ○ |
| 7 | `compose/`: `uploadCore`, then `snapSyncApp` (kills `readGate`×3, roots' uploader copies) | shells Δ | ○ |
| 8 | `flow/` + shell drain + `LaunchDirectives`/`resolveComposition` | shells → ~5 | ○ |
| 9 | `:ui` re-homing + `StatusContainerHost` split + Arrow unification | ledger −1 · modules Δ | ○ |
| 10 | harness collapse: `:adapter:fake` + world on `snapSyncApp` (+ last uploader dupe) | ledger −1 (→0) · modules Δ | ○ |
| 11a | behavior: config → App-Group file, **copy with Keychain write-through** | — (behavior) | ○ |
| 11b | behavior: ledger gains `eventId` (explicit `.sqm` design, declared downgrade stance) | — (behavior) | ○ |
| 12 | behavior: liveness poll · Swift transcriber · `ProtectedData` never created | shells → ~1 | ○ |
| 13a | rename `:app:ios:photokit-extension` → `:app:ios:extension` (+ pbxproj, `ios-archive`, `appShellSources`) | modules Δ | ○ |
| 13b | finale: gates arm as permanent · detekt flips gating · beacon + this plan die | ALL → 0 | ○ |

Deltas are estimates; the beacon's measured numbers win, and a materially divergent step gets a
note in its row.

---

## Step 0 — ⟨R⟩ pin the runtime identity; create the pending zone gates

**As built** (change `pin-runtime-identity-and-zone-gates`; scope settled by interview, two
amendments to the original row: "guards only" became consolidate-then-guard, and the inventory
grew after a sweep). The single highest-leverage hour of the migration. Steps 1–6 move every
string the installed base depends on, none asserted by any test before this step (all `iosMain`
defaults, invisible to the JVM loop). `RuntimeIdentityTest` (`:test:architecture`, gating) pins:
the App-Group id `group.app.snapsync` (Kotlin + both entitlements); the Keychain
service/account **pairs** — the pair is the unit, so a cross-swap fails
(`app.snapsync.deviceid/deviceid` — drift here mints a new device identity and corrupts the
event union for every member, remotely unfixably — plus config, attest ×2, album); the
`NSUserDefaults` keys `discovery.changeToken`, `rejoin.joinedEventId`, `app.snapsync.album.map`;
`ledger.db` and `downloads.db`; the device-manifest layout (`device-manifest/`,
`accumulator.json`, `last-uploaded.json`); the BGTask ids (Kotlin ↔ `Info.plist` agreement) and
background-URLSession ids; the framework `baseName`s (build files). Each literal appears
**exactly once** in production Kotlin with its exact value — made true first by consolidating
the two duplicates (download-store now imports `LEDGER_APP_GROUP` from `:domain:engine`, an
interim iosMain edge that dies at step 4; `ledger.db` got one in-file const) — converting the
migration's worst silent-device-corruption class into a compile-loop failure for all following
steps. Inventory contract of record: the `architecture-guards` spec; adding a pin is a spec
delta.

Same PR: the zone gates the later steps arm — model-purity, ports→model, feature blindness,
flow-no-ports, presentation-imports (import-level approximation: never `ports/`/`flow/`) — each
self-arming with a scope-empty-is-pending twin (the `FakeHonestyTest` pattern), so steps 3a/5/6/9
arm them by creating code, not by writing gates mid-move. Scopes pinned as named assumptions
(design D6): `domain/src/*/kotlin/` zone dirs, `ui/presentation/src`. Verified: all five print
PENDING today; a scratch model file with an engine reference flipped the model gate red with
zero gate edits.

## Step 1 — delete dead weight

Deletion ledger minus two deferred items (Arrow → step 9; `DeviceManifestUploader` ×4 → steps
7/10, since deduping needs shared composition): QR tool + config `jvmMain` + zxing + application
plugin; kotlincrypto ×4; `:capability:device-id` (interface dies — `() -> String` wins;
`KeychainDeviceIdentity` moves to `:domain:keychain` under the step-0 literal pins, then rides
step 4's wholesale keychain move); `EventMetadataSource` merged into join's `EventDetailsSource`;
`LeaveNotifier` inlined; `LoggingPushReceiver`; `LedgerReader`; the dead `status→membership`
edge. Blast radius (verified): app/ios ×2, app/desktop, test/integration, attest, join.
Spec deltas: `event-link` (QR authority), `device-identity` (placement), `event-creation-ui` +
`join-event` (one details client).

## Step 2 — split mixed files

Intra-module file splits so later steps are clean `git mv`s: `DownloadStore.kt` (DTOs + port +
SQLDelight impl), `Ledger.kt`, `SyncEngine.kt`, `EventUnionSource`, `EventCreationClient`,
`EventDetailsSource`, `DeviceFilesSource`, `PushRegistration`, gallery's `RawAsset.kt` /
`DeviceManifestProducer.kt`. No package renames. Measures −6 (two of the counted eight died in
step 1). Behavior-preserving, no spec deltas.

## Step 3a — `:domain` skeleton: `model/` + `ports/` (moves only)

Create `:domain`: targets `jvm() + iosArm64 + iosSimulatorArm64`, **no `iosMain` source set**
⟨R: the no-iosMain rule is about source sets; the targets must exist or no `iosMain` elsewhere
can compile against it⟩; zero `project()` deps; per-zone lib allowlist.

- `model/`: the vocabulary + services + codecs per the coverage map — including ⟨R⟩ the **whole
  `app.snapsync.config` commonMain surface** (`EventConfig`, `Direction`, `Cutoff` +
  `instantToCutoff`/`clampToFloor`/`localToCutoff`, `EventLink` codec, `ConfigSource`,
  `ConfigDecodeResult`) — the step-3 edge kills depend on all of it; `SyncEngine`, `LedgerWriter`,
  `SelectionPolicy` + denylist + `Contribution`, `UploadKeys`, `DeviceManifest` + pure producer
  mapping, `EdgeUploadRequestProvider`, `SyncStatus`/`SyncProgress`, `PermissionStatus`,
  `UploadLivenessNotification` (dies step 12).
- `ports/`: every port interface, **keeping current names** (renames are 3b): `LedgerBackend`,
  gallery enumeration + status, permission pair, `ConfigStore`, keychain seams, `DownloadStore`,
  staging, the backend-need interfaces, `UploadJobPlatform`, `BackgroundScheduler`,
  `PushReceiver`/token source, `DiscoveryStore`, marker, album seams, attest seams, log sink.
  `ProtectedData`/`ProcessSignal` ports are NOT created (deleted from the target; current code
  keeps working from its old home until step 12).
- Tree-wide import rename rides along (~150 files — one PR; move-commit then import-fix commit).
- Kills 5 edges: `join→config`, `membership→config`, `presentation→config`, `upload→push`,
  `download→push`. ⟨R⟩ The 6th (`presentation→event-creation-ui`) survives until step 6:
  presentation's commonMain uses `MutableCreationStatusSource`/`NoOpEventCreator` and its test
  assembles the `CreateEvent` use-case — impl-side types that move with the creation feature.
  Table says −5 (→1) accordingly.

## Step 3b — port need-renames

`LedgerBackend`→`LedgerStore`, gallery enumeration→`PhotoLibrary`, permission pair→`PhotoAccess`,
`UploadJobPlatform`→`BackgroundTransfer`, backend seams→`EventDirectory`/`Enrollment`/
`TransferNotify`/`EventCreation`, etc. Small, IDE-driven, separate from 3a so "mechanical" stays
a verifiable claim on both PRs (⟨R⟩ with zero-review auto-merge, the mechanical claim IS the
review).

## Step 4 — adapters

Prerequisite for feature moves (verified: membership, attest, album, config, download +
domain engine/gallery/keychain/logging/permission/download-store all carry `iosMain` today).
Move every `iosMain` and Ktor/SQLDelight impl into `:adapter:ios:ext-safe` / `:adapter:ios:app-only`
/ `:adapter:generic`, placed by linkage (coverage map). ⟨R⟩ Also in this PR:

- Delete the **two** emptied modules: `:app:ios:photokit-discovery` AND `:app:ios:url-session-upload`
  (its entire source is the two app-only adapters; the controller lives in `app/ios/src`).
- Prune both from `appShellSources`; extend the diagram scan lists with `adapter`.
- `KeychainContainmentTest` owning-module path → the ext-safe adapter.
- Verify the extension-safety gate with a deliberate red (plant a UIKit import in ext-safe,
  watch it fail, remove) — a derived scope that derives to empty is the gate's own failure mode.
- Extension framework now built from ext-safe + generic + domain; **Session A smoke before
  merge** (identity literals byte-identical is step 0's guard; the smoke proves the relink runs
  on-device: same device id, no cursor reset, no re-upload). **Soak after merge.**

## Step 5 — features I: upload · membership · status · trust

Pure commonMain moves into `feature/`: upload (cycle, arm, pump, config-gate,
`clearRequestedOffMain` re-homed — human eyes), rejoin-reconciliation (single-writer placement:
upload), membership (join, leave, details), status projections, trust (attestation policy +
reader path). ⟨R⟩ Retire `StatusEngineBoundaryTest` in this PR (its non-vacuity twin turns
`build` red when `domain/status` empties; the zone gates supersede it). Feature-blindness +
flow-no-ports gates arm (created pending in step 0).

## Step 6 — features II: download · album · creation

Move the rest; `presentation→event-creation-ui` edge dies here (edges → 0); delete every emptied
`capability/*` and `domain/gallery|status|permission|download-store|logging` module (keychain
died into the adapter at step 4). Beacon `targetModules` untouched (they were never in it);
module-set delta shrinks by each deletion.

## Step 7 — `compose/`: `uploadCore`, then `snapSyncApp`

`uploadCore(scope, UploadPorts)` first — kills `readGate` ×3 and the cycle assembly ×3, and ⟨R⟩
the two root-side `IosDeviceManifestUploader` copies die here (the join/generic adapter serves
all); world adopts `uploadCore` additively (verified compatible — full collapse is step 10).
Then `snapSyncApp(scope, AppPorts)`. Human eyes on which `readGate` copy's semantics win (they
differ: the controller reloads config first, the extension does not — the difference is
documented nowhere and must become a comment or a unification decision in the PR). **Session B
covers 7–8; soak after 8.**

## Step 8 — `flow/` + shell drain

One file per trigger; rules sink into features per the coverage map; `LaunchDirectives` parser +
sealed `resolveComposition` in `model/` (the forge×link bug becomes a unit test);
port-state-transition subscriptions install in `compose/`; flow instances built/decorated there,
injected into presentation as the command bundle. detekt count 50 → ~5. Flow sequence diagrams go
live. **Session B before merge; soak after.**

## Step 9 — `:ui` re-homing

`:domain:presentation`→`:ui:presentation`, `:domain:ui`→`:ui:screens`, components →
`:ui:components`. `StatusContainerHost` splits (flow views + feature read-models; commands only
via the bundle — presentation gate arms); `CutoffFormatter` takes `Clock`/`TimeZone` ports;
Arrow/ArrowLevel unify (ledger −1). ⟨R⟩ Extend diagram scan lists with `ui`; freeze protocol;
verification = one `screenshots.yml` dispatch (the only exerciser of `forgeStatusHost`).

## Step 10 — harness collapse

Honest doubles → `:adapter:fake` (FakeHonestyTest arms); `WorldLedgerBackend` dies; ⟨R⟩ world's
`HttpDeviceManifestUploader` dies — deletion-ledger uploader row reaches "keep 1" here (ledger
→ 0). `:test:world` = BackendStore + MiniEdge + levers wrapping fakes; `World` +
`rebuildSources()` replaced by `snapSyncApp(fakeParts)`; integration tests drive flows;
`:app:desktop:ui` folds into `:app:desktop` (⟨R⟩ CLAUDE.md forge-harness runbook command updates
in this PR).

## Step 11a — behavior: config → App-Group file (reinstall = left the event)

⟨R — rebuilt after review; the original design lost joined devices.⟩ The migration lives **inside
the file-backed `ConfigStore` adapter**, not in app startup: read file → on absent, read Keychain
→ on found, atomically write file and return. It runs in **whichever process reads first** — the
OS can schedule the extension before the user ever opens the updated app, and an absent config
reads as a definitive not-joined, which **clears the joined-marker (a false leave on every
joined device)**; the adapter-resident fallback closes that window (app+appex update atomically,
so both carry it). **Copy, don't move**: the Keychain entry keeps being written through (provision
AND leave paths) for the whole soak window; its deletion is a separate later change (13b or
after), so a revert build still finds a live config. The file read distinguishes
unreadable-vs-absent using settle-list ⑥'s answer — which Session A already provided (it is an
input to this design, not an afterthought). Versioned envelope `{v, payload}` + pure migration
function in `model/`.

Spec deltas: `event-rejoin-reconciliation` (reinstall scenario), `device-identity` (annotation),
⟨R⟩ **`upload-lifecycle`** (the entry gate's absent/unreadable semantics now stand on a file),
`event-link`/`join-event` as touched. **Session C before merge; soak after.**

## Step 11b — behavior: the ledger gains `eventId`

⟨R — rebuilt; the original "(eventId, key)" one-liner had no migration mechanics.⟩ The value a
`.sqm` needs is not available in SQL (it lives in config), COMPLETED rows MUST survive (the
`2.sqm` house invariant), two processes race migrations on a WAL DB, and a naive NOT-NULL column
bricks a revert build's INSERTs. The step's proposal MUST contain the actual `.sqm` design;
recommended shape: `ALTER TABLE ADD COLUMN eventId TEXT NOT NULL DEFAULT ''` **keeping PK
`key`** (old binaries keep working via the DEFAULT — revert stays possible), with the
single-writer's first post-migration cycle rewriting `''` rows to the live event id; the
composite-PK recreate is deferred to multi-event, when it pays. If a stronger shape is chosen,
its design.md declares the downgrade stance explicitly. Device verification (Session C):
update-in-place over a joined install → one cycle → **zero new upload jobs**.

## Step 12 — behavior: liveness poll · Swift transcriber

Foreground-gated poll of `aggregates()` replaces the Darwin ding (spec delta: `sync-status`
latency bound); `ProcessSignal` adapters + staticCFunction bridge + `UploadLivenessNotification`
die; `ProtectedData` port never gets created and the defer-queue dies with its old home (Session
A's ④ evidence: expected zero resumes). Swift becomes a pure transcriber (forward `userInfo` and
the `NSUserActivity` whole; lifecycle via `NSNotificationCenter` observed from Kotlin; ① decides
whether the extension result constructs in Kotlin). Swift pin table → ≤1; spec deltas:
`ios-app-shell`, `ios-photokit-upload` as touched. **Session D before merge; soak after.**

## Step 13a — the rename

`:app:ios:photokit-extension` → `:app:ios:extension`, with the verified ride-alongs in one PR:
`project.pbxproj` (embedAndSign run-script + framework search paths), the `ios-archive` composite
action's task path, `ios.yml` references, ⟨R⟩ `appShellSources`, beacon `targetModules`.
Mechanical; gated by `ios-build`.

## Step 13b — finale

Module-set equality → 0. Gates move from the beacon into `:test:architecture` as permanent
failing gates; `detektAppShell` flips `ignoreFailures=false` + joins `check` (⟨R⟩ against the
step-13a-corrected source list — a stale list makes the newly-gating check pass vacuously); the
flow-transcriber generation failure arms as a hard gate; diagram generators drop their
current-state editions; the Keychain config entry's write-through ends (11a's deferred
deletion). Then: delete this module (beacon, `verify` job, this plan), update CLAUDE.md's module
section to the target graph, re-derive the two-frameworks prose. ⟨R⟩ Split from 13a so a falsely
firing armed gate reverts without un-renaming the Xcode project.

---

## Cross-cutting hazards (checked per step)

- **Every merge ships to TestFlight** — "behavior-preserving" means device-behavior-preserving,
  including byte-identical runtime identity (step 0's guard) and store/schema compatibility
  (steps 11a/11b's explicit designs).
- **Fix-forward is the only realistic revert** once a successor lands — hence the soak rule and
  the 11a/11b one-way-door treatment (write-through, DEFAULT-compatible schema).
- **Parallel workspaces**: freeze protocol on the rename steps; everything else rebases over.
- **The beacon's own measurements** survive every step (verified: all scopes derive from dir
  walks/build files; an emptied dir reads as a correct zero) — but its `targetModules` list and
  the deletion-ledger regexes are loud-stale lists that steps touching modules update in-PR.
- **The diagram generators' walk lists are hand-listed** — steps 4 and 9 extend them (see rules).
