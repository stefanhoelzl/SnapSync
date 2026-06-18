## Context

The iOS shell (`ios-first-target`) renders a single static `UiState`. The shared live stack — `LedgerSyncStatusSource` (`:domain:status`), `StatusContainerHost` (`:domain:presentation`) — exists but has never been assembled at runtime: desktop drives `StatusContainerHost` from `PanelController`'s **synchronous fake** sources, never from a real ledger-backed source. So this change is the first runtime assembly of the real stack, and it lands on iOS.

The current seam (`sync-status` spec) requires `SyncStatusSource.status: StateFlow<SyncProgress>` whose value is "always available synchronously … never a placeholder or default," and a `LedgerSyncStatusSource` built by a **suspend factory** that "reads the watcher's current truth before constructing." That contract is satisfiable by a synchronous in-memory fake but **not** by a SQLite-backed source: the first read of persisted state is inherently asynchronous. The suspend factory was the workaround. This design replaces it with an explicit `Loading` state.

Constraints carried in from prior slices and the design doc:
- Permission requires a **full** library grant (`permission-gate`: `.limited/.restricted/.denied → DENIED`).
- The iOS app is a **projection of ledger state written by a background extension while the app is dead** — so cold-launch correctness over a *populated* ledger is the governing concern, even though this slice's ledger is always empty (no producer yet).
- CI gained a parallel `ios-test` job (`ios-ci-tests`) that runs `iosSimulatorArm64Test` — shared `commonTest` now also runs on Kotlin/Native, and a new `iosTest` source set is picked up automatically.

## Goals / Non-Goals

**Goals:**
- Assemble the real `backend → watcher → LedgerSyncStatusSource × permission → StatusContainerHost → StatusScreen` stack on iOS, rendering live `UiState`.
- Ship the first real iOS platform implementation: a PhotoKit permission adapter with correct liveness across the system-Settings round-trip.
- Model the cold-start "reading persisted state" phase honestly as a first-class `Loading` state, removing the `suspend` factory.
- Persist the ledger on-disk via a native SQLite driver; cover the native backend with a CI-gating contract test.

**Non-Goals:**
- Any ledger *producer* on iOS (engine/gallery/uploader) — the ledger stays empty; live status is the permission flow plus `Loading → NeverSynced`.
- A fake/harness ledger writer in the shipping app (the rejected "see InProgress on device" option).
- Code signing, device archive, TestFlight (`ios-testflight-delivery`).
- The App-Group ledger path and cross-process dings (background-extension slice).
- Swift/XCUITest targets (the Swift shell stays trivial).

## Decisions

### D1 — Live data on iOS is delivered as the PhotoKit permission adapter over a real empty ledger
The ledger has no producer this slice, so the ledger projection is permanently `NEVER_SYNCED`; the only seam that genuinely comes alive is **permission**. Rather than stub permission (near-static screen) or port a fake writer (harness in the shipping app), the real deliverable is the **PhotoKit permission adapter**, which produces real on-device behavior (`PermissionAsk → dialog → GRANTED`) exercisable in the simulator. *Alternatives:* stub `GRANTED` (rejected — barely different from today's static screen); fake ledger writer (rejected — drags harness machinery into the app).

### D2 — `Loading` is a first-class state; the suspend factory is removed
A `StateFlow` needs a real initial value; a SQLite read is async; so *something* must absorb "I don't have real data yet." Three ways: (a) `suspend` factory — preserves always-real by making *construction* async, but then the loading phase is represented by "the UI subtree doesn't exist yet," forcing each composition root to invent a loading view; (b) seed with empty `SyncProgress` — **lies**, producing a wrong-then-corrected `NeverSynced → Complete` flash on a populated cold launch (the worst case for the iOS projection model, and invisible until a real device has done background work); (c) **model the loading phase as a real state**. We choose (c): it is the only option whose transitions are all truthful (`Loading → Complete`, never `NeverSynced → Complete`), and it *simplifies* iOS wiring (synchronous construction, no `produceState`/await dance). The `suspend` was load-bearing only under the old always-real contract; `Loading` replaces it honestly. Desktop is unaffected — its fake source emits `Ready` immediately, so it never shows `Loading`.

### D3 — Seam shape: sealed `SyncStatus { Loading | Ready(SyncProgress) }`
`SyncStatusSource.status` becomes `StateFlow<SyncStatus>`, seeded `Loading`. *Alternatives:* nullable `StateFlow<SyncProgress?>` (less invasive, but a nullable sentinel is a weaker model than an explicit sealed type) and `Flow<SyncProgress>` (drops the synchronous current value the container relies on for free conflation/seed). The sealed type is the explicit choice, consistent with the project's taste for typed domain modeling (typed SQL columns, sealed `Outcome`). `SyncStatus` lives in `:domain:status` next to `SyncStatusSource` — it is the seam's vocabulary, not the ledger's.

### D4 — `Loading` is reachable only under `GRANTED`
The container's permission-first precedence means any permission state other than `GRANTED` short-circuits to the gate (`PermissionAsk`/`PermissionDenied`) regardless of the snapshot. So `SyncStatus.Loading` reduces to `UiState.Loading` *only* when permission is `GRANTED`; in every other case the snapshot (Loading or Ready) is irrelevant. This keeps `Loading` semantically precise: "access granted, reading your library."

### D5 — PhotoKit permission adapter: liveness via foreground refresh
PhotoKit's `authorizationStatus(for: .readWrite)` returns **synchronously**, so the permission seam keeps its synchronous-real `StateFlow` (only the *status* seam needs `Loading`; the asymmetry is the whole reason). PhotoKit has **no** change observer, and the user can change access in system **Settings** while we are backgrounded. The adapter therefore treats **app-foreground as a refresh ding**: it observes `UIApplication.didBecomeActiveNotification`, re-reads `authorizationStatus`, and pushes into a `MutableStateFlow`; it also updates from the `requestAuthorization` completion callback. This is the same "platform signal = internal invalidation ding" pattern the design doc prescribes for the ledger. Access level is `.readWrite` (PhotoKit has no read-only level; full-library access is what discovery + resource reads will need). `.limited → DENIED` per the existing contract: the cost is slightly imprecise copy for limited-access users (told "denied"), accepted for v1 over a fourth state. The adapter is a single object implementing both ports; constructed once, observer registered for app lifetime (no teardown in v1); `MutableStateFlow` set is thread-safe, so callbacks on arbitrary queues need no extra scope.

### D6 — iOS ledger backend: native driver, on-disk app sandbox
`iosLedgerBackend()` (`:domain:engine` `iosMain`) = `NativeSqliteDriver(LedgerDatabase.Schema, "ledger.db") → LedgerDatabase(driver) → SqlDelightLedgerBackend`. The commonMain `SqlDelightLedgerBackend` + `LedgerDatabase(driver)` already do all the work. **On-disk app-sandbox** location (not in-memory: keeps persistence honest and exercises the real driver/schema/path; not App-Group: that needs an entitlement + signing, has no cross-process writer to justify it this slice, and belongs to the extension slice). `iosLedgerBackend()` is the single site naming the path, so the sandbox → App-Group migration is a one-line change later.

### D7 — Composition root: `SnapSyncRoot` Kotlin singleton, Swift untouched
A small `SnapSyncRoot` in `:app:ios` `iosMain` owns an app-lifetime `CoroutineScope(SupervisorJob() + Dispatchers.Main)` and assembles the stack synchronously (possible now that nothing suspends). `MainViewController` renders `root.host.container.stateFlow`. *Alternative:* construct/inject the root from `iOSApp.swift` — its only real advantages (scene-aware lifecycle, scope teardown/recreate, multi-window sharing) don't apply to a single-window, single-screen, process-lifetime v1, and the permission adapter self-observes foreground so Swift need not forward lifecycle. **Tripwire:** move ownership to Swift when scene-aware lifecycle or scope-recreate (multi-window, reset/logout) is needed. The scope is app-lifetime (not `rememberCoroutineScope`) so the source collector + Orbit container outlive recomposition. The watcher's `aggregates` stream gets `.flowOn(Dispatchers.Default)` so SQL never runs on the main thread once the ledger grows.

### D8 — `UiState.Loading` rendering
New `UiState.Loading` (presentation) and `StatusIndicator.Loading` (design system) rendered as an **indeterminate** `CircularProgressIndicator()` (distinct from determinate `Progress(fraction)` and from `NeverSynced`). Screen shows `StatusHero(Loading, "Loading …")` — no detail, no button (nothing for the user to do; it auto-resolves). *Alternative:* reuse `StatusIndicator.Waiting` (rejected — `Waiting` means "suspended/idle," reads as stuck for an active read).

### D9 — Testing split
The new `Loading` logic lives in `commonTest` (`StatusContainerHostTest`, `LedgerSyncStatusSourceTest`) which the `ios-test` job now also runs on Kotlin/Native — free Native coverage. `StatusScreenTest` (JVM Compose UI) gains a `Loading` case (asserts "Loading …" + a present progress indication, the mirror of the absent-indication assertions). A new `:domain:engine` `iosTest` runs the existing `LedgerBackendContract` against a `NativeSqliteDriver` (in-memory, for isolation) — the gap the `ios-test` job exists for (native driver + schema + enum/`kotlin.time.Instant` adapters on Native), now CI-gating instead of manual. The PhotoKit adapter stays **manual** (the system permission dialog can't be driven by a unit test; no XCUITest target). On-disk path resolution + cross-launch persistence is verified by the manual walk-through.

## Risks / Trade-offs

- **Wrong-frame regression if `Loading` is ever bypassed** → If a future change reintroduces a seeded-empty initial value, a populated cold launch flashes `NeverSynced → Complete`. Mitigation: the `LedgerSyncStatusSourceTest` asserts the `Loading → Ready` transition; the seam type makes `Loading` unavoidable at construction.
- **Native SQLite driver behavior on iOS unverified** → schema creation / adapters could differ on Kotlin/Native. Mitigation: the new `:domain:engine` `iosTest` contract test runs on the simulator in CI (gating).
- **PhotoKit foreground-refresh is manual-only** → the Settings round-trip has no automated coverage. Mitigation: explicit manual walk-through step; the behavior is small and localized to the adapter.
- **`.limited → DENIED` copy imprecision** → limited-access users see "Photo access denied." Accepted for v1 (existing contract); revisit only if a real report appears.
- **SQL on the main thread** → `aggregates()` runs on the collector's dispatcher. Mitigation: `.flowOn(Dispatchers.Default)` on the watcher's aggregate stream; ledger is tiny/empty this slice regardless.
- **Simulator device selection on `macos-26`** (inherited R2 from `ios-ci-tests`) → `iosSimulatorArm64Test` auto-selects a standalone simulator; a missing default device fails with "no matching device." Our new `iosTest` boots the *same* simulator the existing `ios-test` job already boots, so it inherits a proven-working selection — **no proactive pin**. If a run fails, pin once at the **root** `build.gradle.kts`:
  ```kotlin
  allprojects {
      tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>()
          .configureEach { device.set("iPhone 16") }   // device-type NAME, not a UDID
  }
  ```
  The knob is `KotlinNativeSimulatorTest.device` (a `Property<String>`), **not** `testRuns["test"].deviceId` (the `ios-ci-tests` design.md phrasing is imprecise — there is no `deviceId` on the test run). Pin a device-type *name* (UDIDs are ephemeral per runner image).

## Migration Plan

- Domain seam change is source-compatible at the call sites within this repo (only `StatusContainerHost` and the desktop/iOS roots consume the seam). Desktop's fake source updates to expose `StateFlow<SyncStatus>` emitting `Ready(...)`; no desktop behavior change.
- No runtime data migration (the ledger schema is unchanged; only a new platform driver instantiates it).
- Rollback: revert the change; no persisted-format or external-contract impact.

## Open Questions

- None blocking. The simulator-device pin is resolved as fix-on-failure (above).
