# Design — establish-shared-composition

## Context

Migration step 7 (PLAN.md). Steps 3a–6 put every port, feature, and adapter in its target seat; the
composition itself is still written three times. This step creates `compose/` and the two shared
composition functions the `module-architecture` law names (`uploadCore`, `snapSyncApp`), under the
step's one sanctioned semantic decision (the readGate unification, D1 below) and otherwise
behavior-preserving wiring equivalence. RUN.md model: implementer never commits; two adversarial
reviews (law + behavior) precede the commit; the world-harness smoke runs after.

## Goals / Non-Goals

- Goal: one upload-cycle assembly; one app-graph assembly; readGate ×3 → ×1 with the divergence
  resolved on the record; `IosEnrollment` ×2 dead; D4 (`ResourceEnumerator` → compose/) repaid;
  world on `uploadCore` additively.
- Non-goals: `flow/` and the command bundle (step 8 — coordination lambdas stay shell-supplied);
  `LaunchDirectives`/`resolveComposition` (step 8 — tier selection stays a shell thunk);
  the receiver pair's re-homing (`:capability:upload`, step 8); the world's full collapse onto
  `snapSyncApp` and the world `HttpEnrollment` death (step 10); any behavior change beyond D1.

## Decisions

### D1 — The unified readGate reads the port, fresh, and nothing else (extension semantics win)

The three copies diverged in exactly one line: `UrlSessionUploadController.readGate()` called
`configSource.reload()` (a concrete `KeychainConfigStore` method that re-reads the Keychain **into
the UI-facing `ConfigSource.config` StateFlow**) before the three-state `read()`; the extension's
gate called `read()` alone; the world's was a hand-rolled fake. The unified gate in `uploadCore` is
the extension's shape: one fresh `ConfigReader.read()` + the device-identity probe + the host read,
combined by the already-tested `cycleGate`.

Why this is the right resolution, not a coin flip:

1. **The spec already decides it.** `upload-lifecycle`, "The upload cycle owns its entry decision":
   a root "SHALL supply **only** the platform reads the decision consumes — the membership read, the
   device-identity probe, and the build-time host". A StateFlow refresh is not among the inputs; it
   is a read-model side effect riding in the gate.
2. **The law makes the alternative inexpressible.** `reload()` exists only on the concrete
   `KeychainConfigStore`; the `ConfigReader`/`ConfigSource` ports do not carry it. A compose/-seated
   gate can only reach ports, so the port-pure semantics are the only lawful unified form.
3. **The gate outcome is provably identical.** `reload()` is `read()` + a StateFlow assignment; the
   controller's gate then decided from a *second*, fresh `read()`. Dropping the reload changes no
   gate outcome on any input — only the side effect on the app-process StateFlow.
4. **The side effect's one real job is already owned elsewhere.** The StateFlow can be stale in
   exactly one production scenario: the store was constructed while the device was locked
   (pre-first-unlock background launch), seeding `null` for a joined device. The repair is
   `SnapSyncRoot`'s `ProtectedDataGate.runWhenAvailable("reloadConfigOnUnlock") { config.reload() }`
   hook — and every launch path that can construct the store while locked registers it: the
   background-URLSession relaunch, the silent push, and the download backstop all touch the
   `protectedData` lazy in their entry point; the upload-heartbeat `BGProcessingTask` cannot run
   pre-first-unlock at all (Apple's documented `BGTaskScheduler` guarantee, already cited as a
   forcing proof in the controller). In-process config writes (`save`/`clear`) update the StateFlow
   synchronously, and no other process writes the config item — so post-unlock the StateFlow cannot
   drift again. The per-cycle reload was therefore redundant for correctness; its deletion is
   recorded here and at the unified site as the step's one semantic change.

Consequence accepted on record: between a locked cold launch and the unlock, app-process
`config.config.value` consumers (push receiver active-event guard, `downloadEnabled`, album lookup)
see `null` exactly as they do today — cycles skip on `CycleGate.Skip` regardless — and the repair
moves from "next pump cycle after unlock" to "the unlock itself" (strictly no later; the gate hook
fires on the availability transition).

### D2 — `uploadCore(scope, UploadPorts): UploadCycle`, ports-bundle of port types + thunks

`UploadPorts` carries port interfaces (`ConfigReader`, `LedgerStore`, `BackgroundTransfer`,
`DiscoveryStore`, `DeviceFilesSource`, `JoinedEventMarker`, `DeviceManifestStore`, `Enrollment`,
`SuppressionSource`) plus the thunks whose call-time semantics are load-bearing: `deviceId:
() -> String` (throws `KeychainUnavailable` while locked; each root keeps its own caching), `host:
() -> String?` (the extension reads the bundle per gate call today — preserved), `token`,
`albumExcludedAssetIds` (per-root failure posture preserved — the app tier's admit-on-doubt wrapper
stays where it is), `onBatchUploaded` (the notify sender lives in `:capability:push`, unreachable
from `:domain`). The `AlbumCoordinator` (feature/album) is taken as an instance and `placeInAlbum`'s
`denormalizeAssetId` mapping — byte-identical in all three call sites — is unified inside.
The reconciler and manifest producer are constructed **lazily** inside `uploadCore` so the device id
resolves on first in-cycle use, exactly as each root arranged it (an eager resolve would throw at
composition time on a locked device). Per the law the composition function receives the process
`CoroutineScope`; nothing consumes it until step 8 installs the port-state-transition subscriptions
here, and the parameter is kept so the signature does not churn (recorded at the site).
*Rejected:* returning a richer `UploadCore` holder (nothing needs it yet); folding the pump in (the
pump is one tier's mechanism, not shared composition).

### D3 — `snapSyncApp(scope, AppPorts): AppCore` — lazies mirror the root's construction timing

`AppCore` exposes the composed graph as `by lazy` properties (attestation, upload arm, join/leave/
create, download controller + jobs + receiver + status source, album coordinator, gallery/ledger
counts/sync-status read-models) so first-touch construction order is byte-equivalent to the root's
previous lazy web — the property that matters on a locked background launch, where an eager
`deviceId` resolve would abort. What stays in the shell, each with its step: the coordination
functions `provisionEvent`/`refreshStatusSources`/`fetchAndStoreName` and the `onMinted`/
`notifyLeave` lambdas (flow material, step 8 — `AppPorts` takes them as inputs); producer tier
selection (`resolveComposition`, step 8 — `AppPorts.uploadProducer` is a thunk); the push fan-out
(`FanOutPushReceiver` lives in `:capability:upload`, step 8); `StatusContainerHost` +
`MutableAttestedSource` (presentation — `:domain` cannot reach it, re-homed step 9); the
permission-grant collectors (port-state-transition subscriptions, installed in compose/ at step 8);
every platform adapter construction and env read. *Rejected:* passing `AppPorts` fields as thunks
wholesale (doubles the ceremony to preserve timing that only matters for the identity resolve, which
stays thunked).

### D4 — `ResourceEnumerator` seats in `compose/` (repays step 6's D4)

The class is the decision-free composition `PhotoLibrary = resourcesFrom ∘ RawAssetSource` — port
composition, compose/'s job by the zone definition ("holds the shared composition"). Consumers
update imports: the ext-safe PhotoKit walk (`PhotoLibraryResourceEnumerator` delegates to it), the
world, and the `:domain:gallery` stay-behind test. An adapter importing a compose/ helper is lawful
(adapters see all of `:domain` via `api(":domain")`) and dissolves at step 10 when the fakes re-home.

### D5 — `HttpEnrollment` serves both roots; the world's copy waits for step 10

The three production `Enrollment` impls were byte-identical (verified); both `IosEnrollment` copies
die and each root passes `:adapter:generic`'s `HttpEnrollment` into its ports bundle (both modules
already depend on `:adapter:generic`). The world's `app.snapsync.world.HttpEnrollment` — also
byte-identical — dies at step 10 per PLAN (the deletion-ledger row reaches "keep 1" there); killing
it early would silently shift step 10's expected ledger Δ. Row measurably shrinks ×4 → ×2 now.

### D6 — World adoption is additive and honest

`World.cycle` is built by `uploadCore` over the world's fakes; the `membershipUnreadable` lever
becomes a `ConfigReader` adapter (`Unavailable(status = -25308)` — `errSecInteractionNotAllowed`,
the real locked-device OSStatus, so the unified skip-detail line reads like a device's). The
world-local `readGate()`, `reconciler()`, and `manifestProducer()` are deleted — no consumer outside
`World` (grep-verified); `manifestUploader` stays public (the join-gate integration test and the
world inspector enroll through it). The world's skip log line changes from "world: membership forced
unreadable" to the unified forensics string; no test asserts on it (verified). Log-tag deltas in the
world only (reconciler logs under the cycle's tag): accepted, test infra.

## Risks / Trade-offs

- [D1 drops a side effect on a shipped tier] → gate outcome provably identical; StateFlow repair
  path enumerated per launch path; flagged as THE human-eyes item for the operator pause.
- [Eager-at-first-touch adapter constructions inside `AppPorts`/`UploadPorts` vs per-object lazies]
  → all such constructions are side-effect-free object allocations — with two named filesystem
  exceptions verified inert (the App-Group container-URL lookup + best-effort `setAttributes` in the
  store factories, both already performed by the same paths via `iosLedgerStore()` at the same
  moment; the SQLite open itself is deferred to the first executed query by the native driver's
  lazy connection pool — a library-internal guarantee worth re-checking on driver bumps) — (no
  Keychain/PhotoKit read
  in any moved constructor — verified per adapter); first touch happens at the same entry points as
  before (forge-mode guards precede every one), and the identity resolve stays thunked.
- [`uploadCore`'s unused `scope` invites deletion] → kept per the law's signature; the step-8
  subscriptions consume it; comment at the site names this.

## Migration Plan

Single working-tree change (branch `arch`, RUN.md model). Rollback = revert the diff; no durable
state, schema, or identity string moves. Gates: `./gradlew build`,
`compileIosMainKotlinMetadata`, `architectureDiagrams`, `:test:architecture:test`, beacon
before/after, `:test:integration` + `:test:world` JVM tests; world-harness smoke post-commit.
