## Why

A review of every function-typed seam in the tree (2026-09-22) found thirteen defects: silent loss of staged
downloads, a valid device token wiped by an unrelated `401`, sibling work cancelled by one throw, a
reconfigure that proceeds on a config it never saved, main-thread port I/O, out-of-order selection
snapshots, crashes from exceptions escaping ObjC blocks, and re-entrant taps. Few of them are caused by
lambdas as such. They fall into five **classes**, and every class passes today's build because nothing
mechanical rejects it. This change fixes the thirteen and closes each class with a rule plus, where the rule is
mechanical, a gate. Fixing only the instances would leave the same shape free to come back.

The five classes, with the defects that instantiate each (defect ids are the review's, used throughout this
change):

- **G1: the seam's contract is invisible.** A bare function type states no failure behaviour, no lane,
  no out-of-process reach, and whether it must be wired at all. The instances are B1 (`onStaged` is a
  late-bound nullable `var`, so a cold background relaunch drops every staged download), B3 (`onRejected()`
  does not say which token was rejected), B6 (query lambdas outside the lane gate), the seam-gate pins
  whose stated reasons are false, and the silent `= {}` defaults.
- **G2: the test world is wired differently from production.** The world forces lazies that production
  never forces (this masked B1), and binds its own bodies for `provision`, `refreshAttestation` and
  `registerPush`.
- **G3: failures are lost or go to the wrong place.** B4 (a throwing child cancels its siblings), B5
  (a failed save is swallowed and the steps after it run anyway), B8 (a `Boolean`/`NSError**` result is
  ignored), B10 (Kotlin throws inside ObjC blocks), B11 (`deviceId()` escapes a function documented as
  never throwing), B12 (`CancellationException` swallowed at about 20 sites).
- **G4: distinct states are merged into one.** B2 (every `401` is read as "token rejected", including
  `/attest/*` "stale challenge"), B3, `joined` (merges "unreadable" into "not joined"), `isGranted`
  (means *usable* access, not full).
- **G5: concurrency and re-entrancy.** B6 (main thread), B9 (selection snapshots emitted out of order),
  the `outstandingImports` race, B7 (`reconfiguringState` survives a membership), B13 (switch cancel
  undone, double create), and the rename flag.

## What Changes

- **G1**
  - No function-typed `var` in production source; callbacks are constructor parameters (fixes B1).
  - No default value on a function-typed constructor parameter in production source; Compose content slots
    are exempt.
  - The presentation-facing queries (`shareableCount`, `loadJoinDetails`) move into a `UserQueries` bundle
    built and lane-decorated in `compose/`, and the lane gate covers it (fixes B6's lane half).
  - A function type is allowed only for a callback into the core that cannot throw and does not reach out
    of the process. Anything else is a named port or `fun interface`.
  - The seam gate widens from `*Ports` bundles to every function-typed constructor parameter in `feature/`
    and `compose/`.
  - `deviceId`, `reloadConfig`, `scheduleBackstop`, the extension's `admission`, `albumExcludedAssetIds`,
    `appVersion` and `host` become ports or plain values accordingly.
- **G2**
  - An `AppPorts` seam binds a platform adapter, never core code: `provision`, `refreshAttestation` and
    `registerPush` are built in `compose/`.
  - The world boots cold: its construction forces no core lazy.
  - Every OS entry point is exercised from a cold core. On rebase this is main's `PlatformEntriesContract`
    (driven through the inbound port over a fresh World), which the cold world now makes honest.
- **G3**
  - `runCatchingCancellable` in `model/`. `runCatching` and `catch (Throwable|Exception)` are banned in
    production source outside the ObjC-boundary helpers (fixes B12, B11).
  - Flows fan out through one isolating helper. A bare `coroutineScope { launch }` is banned in `flow/`
    (fixes B4).
  - Multi-step use cases declare each step `required` or `bestEffort`. A failed required step aborts and
    returns a failure outcome (fixes B5).
  - `objcCallback { }` wraps every block and delegate callback handed to ObjC, and `checkNSError` wraps
    every `Boolean`/`NSError**` call, both guarded by a scan (fixes B10, B8).
- **G4**
  - HTTP outcomes are classified per route. The credential interceptor reacts only to a rejection of a
    token-bearing request on a gated route (fixes B2).
  - **BREAKING (v2 only):** `/api/v2/attest/token` and `/api/v2/attest/renew` answer a stale challenge with `409`
    instead of `401`. `/api/v1` is frozen and keeps `401`. The attest routes are split per version
    because they are shared today.
  - Invalidation callbacks name their subject (`onRejected(sentToken)`), and the store clears by
    compare-and-clear (fixes B3).
  - Reads that can be unknown return a sealed `Known | Unreadable`, never a bare `null` or `Boolean`. The
    early return on unknown is logged. `isGranted` is renamed `hasUsableAccess`.
- **G5**
  - Mutable state reached from OS callbacks is confined to a named serial lane, including ordered
    snapshot emission (fixes B9 and the `outstandingImports` race).
  - Non-idempotent presentation commands mark themselves in flight before their first suspension, and apply
    a result only if the state it started from is still current (fixes B13 and the rename flag).
  - Membership-scoped UI state is keyed by the membership, not cleared exit by exit (fixes B7).
  - `:ui:screens` takes no `suspend` function-typed parameter (fixes B6's thread half).

## Capabilities

### New Capabilities

None. Every rule lands in an existing capability.

### Modified Capabilities

- `module-architecture`:
  - The lambda-vs-port rule is tightened (no throwing or out-of-process function types; no core glue in
    `AppPorts`).
  - Trigger flows isolate their children.
  - New laws: queries cross a lane-gated door; no late-bound callbacks; no defaulted function-typed
    parameters; catch sites keep cancellation; ObjC boundaries contain throws; callback-reached state is
    confined; unknown-capable reads are sealed.
- `architecture-guards`:
  - The composition seam gate widens.
  - The command-lane gate covers `UserQueries`.
  - New gates: callback-var, lambda-default, catch, flow fan-out, ObjC-boundary, confinement,
    screens-take-no-suspend-seam, the world boots cold.
- `ios-app-shell`: the composition root no longer supplies `reloadConfig`/`scheduleBackstop` as lambdas.
  Both become ports, and `refreshAttestation`/`registerPush` leave `AppPorts`.
- `harness-world-model`: the world boots cold and binds no core glue. Its operator provision becomes an
  explicit operator lever rather than a second body for `AppPorts.provision`.
- `device-attestation`:
  - Only a token-bearing request's rejection on a gated route invalidates the credential, and only that
    credential.
  - A v2 stale challenge is `409`, served by v2's own attest routes.
  - `api-endpoints` needs no delta: its route table is unchanged, and it defers the attest contract to this
    capability.
- `reconfigure-membership`: a failed config save aborts the reconfigure and is reported.
- `photo-download`: a staged resource reaches the controller on every entry point, including a cold
  background relaunch.
- `limited-photo-access`: selection snapshots are emitted in change order, and none is emitted after
  observation ends.
- `upload-lifecycle`: an unreadable membership is not treated as "not joined". The transition logs and
  retries rather than returning silently.
- `join-share-count`: the count query runs on the core lane and its failure reduces to "unavailable".

`push-registration` needs no delta. B11 breaks its existing "handled without throwing to the caller"
requirement, so fixing B11 is conformance, not a contract change.
- `sync-status-screen`: re-entrancy (in-flight before the first suspension; stale results dropped) and
  membership-keyed surface state.

## Impact

- **Code:**
  - `:domain` (every zone), `:adapter:generic:app` (interceptor, HTTP adapters), `:adapter:ios:ext-safe`
    and `:adapter:ios:app-only` (ObjC callbacks, snapshot source, schedulers).
  - `:app:ios` and the extension root (seam bindings shrink).
  - `:ui:presentation` / `:ui:screens` (the `UserQueries` bundle, guarded commands, membership-keyed
    state).
  - `:test:world`, `:test:integration`, `:test:architecture` (the new gates), and `api/` (the v2 attest
    router).
- **API:** `/api/v2/attest/{token,renew}` stale-challenge status changes. A shipped client already on v2
  that sees the new status treats it as a failed renewal and retries at the next wake. It does not wipe
  its token. v1 is untouched.
- **Build:** about eight new `:test:architecture` gates. Each fails closed on novelty, per the existing
  convention.
- **Size:** this is an umbrella change. `tasks.md` orders it so that each group ships as its own PR,
  G1 first, because G2 and G5 build on its query bundle and seam rule.
