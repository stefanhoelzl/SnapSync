## Why

`event-link`'s requirement *"An unreadable config is not an absent config"* — the false-leave fix from
`changes/archive/2026-07-14-fix-locked-device-keychain-access` — is written for **one tier**. It names
"the extension" five times, and its three scenarios all begin *"the extension's cycle"*. The app-driven
tier (iOS 18–26.0) reads `configSource.config.value`, a two-state `StateFlow` whose own KDoc says it
*"cannot express unreadable"* and is *"fatal for the reconciler"*, then treats `null` as a leave and
clears the `joinedEventId` marker. It is not violating the contract; the contract never covered it.

**This change does not claim a demonstrated bug.** The unreadable window is narrow and its reachability on
the app-driven tier is **unproven in both directions** — see design *Context / Reachability*. The claim is
narrower and does not depend on it: one tier carries a guard the other lacks, for a state both can in
principle reach, and the guard is the one this project already decided to keep *"even though the attribute
makes it improbable"*. Two prior changes fixed this exact asymmetry after it shipped; this one closes the
third instance before it does. If a demonstrated defect is the bar, this change does not clear it — the
duplication and the unstated ports below stand on their own.

This is the third instance of one pattern: a gate written into the root of the OS-invoked tier, absent
from the app-invoked one. `2026-07-12-fix-app-driven-upload-lifecycle` (reconcile) and
`2026-07-16-fix-upload-direction-gate` (direction) were the first two, and both were fixed the same way —
by moving the decision into `UploadCycle`, the choke point every trigger on every tier funnels through.
The config gate is the one that has not had that treatment, because it sits **upstream** of the cycle:
it decides whether a cycle is built at all. There is no choke point to move it to. This change creates
one.

## What Changes

- **`UploadCycle` owns the entry gate.** It takes a required `readGate: () -> CycleGate` and performs the
  three-state read itself, so `Skip` / `NotJoined` / `Run` is decided in one tested, tier-neutral place.
  The cycle becomes long-lived (constructed once per process) rather than per-run; the membership — and
  with it `Contribution` — is re-read inside `run()`.
- **`CycleGate.Run` carries what the cycle needs**: `eventId`, `Contribution`, `saveToAlbum`, `host`. All
  primitives plus `Contribution` (`:domain:gallery`) — **no new module dependencies**. `cycleGate()` keeps
  its existing platform-free signature and stays where it is.
- **`CycleGate.Skip` carries a `detail` string** so the extension's forensic skip log ("config
  status=…, deviceId readable=…") survives the move into shared code. **BREAKING** for `CycleGateTest`'s
  `assertEquals(CycleGate.Skip, …)` equality assertions.
- **The leave-side reconcile moves into the cycle.** `runCatching { reconciler.reconcile(null) }` is
  currently written identically in both roots and in `:test:world` — three copies of one decision.
- **BREAKING: `UploadCycle`'s remaining permissive defaults are removed.** `onDiscovery = {}`,
  `suppressedAssetIds = { emptySet() }`, `albumExcludedAssetIds = { emptySet() }`, and
  `onBatchUploaded` become required, following the precedent `reconcile` and `contribution` already set —
  each was made required only *after* it caused a shipped bug. Required does not mean "must have one"; it
  means the answer is stated at the call site instead of inherited. `UrlSessionUploadController`'s own
  `albumExcludedAssetIds = { emptySet() }` default goes with them.
- **The roots shrink to translation.** `UploadExtensionRoot.process()` reduces to `runBlocking` +
  liveness + the pending→`PROCESSING` requeue (~6 lines from ~150).
  `UrlSessionUploadController.runCycle()` reduces to one line (from ~65). What remains in each is a
  ~3-line lambda mapping that platform's storage into `cycleGate()`'s arguments.
- **The app-driven tier gains the device-id probe** it does not have today, via the shared gate. It
  currently holds `deviceId` as a resolved `String` constructor parameter and cannot express "unreadable
  this cycle"; an unresolvable id throws rather than skipping cleanly.
- **`:test:world` composes the real cycle** instead of mirroring it. `World.runUploadCycle()` stops
  re-implementing the assembly; the harness supplies a `readGate` over its config cell and gains an
  `unreadable` lever, making the `Skip` path reachable from `:test:integration` for the first time.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-link`: the *"An unreadable config is not an absent config"* requirement becomes **tier-neutral** —
  "the upload cycle" replaces "the extension" throughout, and its scenarios cover both tiers. This is the
  requirement whose single-tier phrasing is the defect.
- `upload-lifecycle`: gains the **cycle-entry** contract — the upload cycle SHALL perform the three-state
  membership read itself and decide skip/leave/run before any walk; and every selection-policy and
  side-effect port SHALL be required, with no permissive default.
- `ios-url-session-upload`: an unreadable config or unresolvable device id SHALL skip the cycle cleanly on
  this tier rather than reading as a leave.
- `ios-photokit-upload`: the extension root's `process()` SHALL contain only what is tier-specific —
  `runBlocking`, the liveness notification, and the pending→`PROCESSING` requeue.
- `harness-world-model`: the world SHALL compose the real cycle rather than mirror its assembly, and SHALL
  be able to model an unreadable membership.

## Impact

**Code:**
- `:capability:upload` — `UploadCycle` (entry gate, required ports, long-lived), `UploadConfig.kt`
  (`CycleGate.Run` payload, `Skip(detail)`). No new module dependencies.
- `app/ios/photokit-extension/…/UploadExtensionRoot.kt` — `process()` shrinks; gate lambda added.
- `app/ios/…/UrlSessionUploadController.kt` — `runCycle()` shrinks; gains the gate and the id probe;
  `deviceId: String` becomes a probe-able port.
- `:test:world` — `World.runUploadCycle()` composes; `unreadable` lever added.

**Tests:**
- `CycleGateTest` — mechanical update for `Skip(detail)`; gains the `deviceIdReadable=false` case.
- `UploadCycleTest` (44 tests) — unaffected in kind; the phases it covers are untouched. New tests for the
  entry gate.
- `:test:integration` — new coverage: an unreadable membership does not clear the join marker, on both
  tiers.

**Risk:** every `UploadCycle` construction site stops compiling until it states its ports. That is the
review. The live defect is narrow — `AfterFirstUnlock` means an unreadable config needs a reboot with no
unlock — so this is a latent fix, not an incident response.

**Not in scope:** the Konsist choke-point guard (`architecture-guards`), which this change unblocks by
removing the roots' legitimate `reconcile(null)` call but does not itself add; and the attestation-token
gap in `:test:world`'s engine construction.
