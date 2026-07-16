## Context

Three changes have now fixed the same bug: a decision written into the composition root of the OS-invoked
tier, absent from the app-invoked one.

| Change | The decision | How it was fixed |
|---|---|---|
| `2026-07-12-fix-app-driven-upload-lifecycle` | re-join reconcile | moved into `UploadCycle`; made a **required** parameter |
| `2026-07-16-fix-upload-direction-gate` | participation direction | moved into `UploadCycle.run()`; made a **required** parameter |
| *this change* | the three-state config read | — |

The second one named the mechanism precisely: *"an invoker-gate is only as sound as its enumeration of
invokers."* Its D1 rejected gating at `SnapSyncRoot` for the reason D4 of the superseded document had
already given — *"it parks behavior in a module the hard rule declares untested, which is how this
shipped."*

**Why this one is different.** Both prior fixes had somewhere to move the decision *to*: `UploadCycle.run()`
sits downstream of every trigger, so a gate about *what to upload* lands there naturally. The config gate is
**upstream** of the cycle — it decides whether a cycle is constructed at all, and whether the leave-side
reconcile fires. There is no shared choke point. Both roots read config, both decide, and one of them
decides wrong:

```
        THE DIRECTION GATE (fixed)                  THE CONFIG GATE (this change)

  OS ──┐                                       OS ──┐
  fg ──┼──▶ root ──▶ UploadCycle.run()         fg ──┼──▶ root ──▶ [decide] ──▶ UploadCycle
  bg ──┤              ▲                        bg ──┤             ▲
  push ┘              └── ONE place            push ┘             └── TWO roots, TWO answers
```

**The contract carries the defect too.** `event-link`'s requirement *"An unreadable config is not an absent
config"* says "the extension" five times and every scenario opens *"the extension's cycle"*. The
app-driven tier is not violating it — it was never in scope. The single-tier phrasing is the same
invoker-thinking, one level up from the code.

Constraints that shape the fix:

- **The roots are unreachable from tests, permanently.** `UploadExtensionRoot` and
  `UrlSessionUploadController` are `iosMain`-only by nature (`NSBundle`, Keychain, `CFNotificationCenter`).
  `:test:world` is `commonMain`/`jvm`. No refactor makes the roots testable; the only lever is making them
  small enough that what is left cannot hold a decision.
- **`:capability:upload` takes primitives, deliberately.** `cycleGate(configReadable: Boolean, eventId:
  String?, host: String?)` — its KDoc: *"this module stays event- and platform-agnostic: it takes an
  `eventId` string, not the config type."* Everything the cycle needs is a primitive or `Contribution`
  (`:domain:gallery`, already a dependency).
- **`deviceId` is binary, not three-state.** `resolveOrMint` maps `Found → value`, `Absent → mint`,
  `Unavailable → throw`. There is no "no id" outcome; absence mints. A `Boolean` roll-up is the whole truth.
- **The device-id resolve is per-process on both tiers already.** `KeychainDeviceIdentity` caches `by lazy`;
  the extension's per-cycle probe is per-process because its process dies each cycle.
- **`AfterFirstUnlock` makes the defect improbable, not impossible** — the same posture
  `2026-07-14-fix-locked-device-keychain-access` took: *"The attribute makes it improbable; the three-state
  read makes it impossible."*
- **`main` is the public alpha channel** — every merge reaches public TestFlight testers silently.

### Reachability: unproven, and the change does not rest on it

The window is narrow. `AfterFirstUnlock` means a *locked* device reads fine; only a boot with **no unlock
since** leaves the membership unreadable. Whether the app-driven tier can run a cycle in that window was
investigated and **not settled**:

| Trigger | Can it run before first unlock? | Evidence |
|---|---|---|
| `BGProcessingTask` (`runUploadHeartbeat`) | **No** | WWDC 2019 §707: *"We do guarantee that we won't start your task until the user first unlocks their device."* Path closed. |
| Background `URLSession` relaunch (`handleBackgroundUrlSession` → `onSessionEvents`) | **Unknown** | No Apple documentation either way. Weak inference only: Apple states the guarantee for `BGTaskScheduler` explicitly, which would be redundant if no mechanism could run in that window. |
| Silent push (`onSilentPush`) | Moot | Already behind `ProtectedDataGate`. |
| `onStart` / `onForeground` | **No** | Require an unlock by definition. |

So the honest position: **one plausible path, unproven.** It is not excluded either — and the failure it
would produce is silent (a cleared marker, then one full re-join reconciliation with a complete PhotoKit
re-enumeration at ~110 ms per asset).

Two things follow, and they shape the design rather than decorate it:

- **The `ProtectedDataGate` cannot be the fix for this path.** `UIApplication.isProtectedDataAvailable` is
  false whenever the device is **locked**, not merely before first unlock. Gating the upload path on it
  would defer every locked background wake — which is precisely when this tier is supposed to upload. The
  ungated `runUploadHeartbeat` is therefore *correct*, and the three-state read is the only protection
  available to the upload arm.
- **`ios-app-shell`'s deferral requirement conflates two conditions.** It requires background work reading
  *"the Keychain-backed device id and event config"* to defer on `isProtectedDataAvailable` — but file
  protection (false whenever locked) and Keychain accessibility (false only before first unlock) are
  different conditions. Read literally, that requirement would break background upload. It is out of scope
  here, and noted in Open Questions.

**The change therefore does not claim a demonstrated defect**, and no task pretends to demonstrate one (see
tasks §1). It claims an asymmetry: the OS-invoked tier has the guard, the app-invoked tier does not, for a
state neither can rule out. That is the same bet `fix-locked-device-keychain-access` already took — it kept
the three-state read *knowing* `AfterFirstUnlock` had made the state improbable, on the reasoning that *"the
attribute makes it improbable; the three-state read makes it impossible."* Extending that to the second tier
is consistency with a decision this project already made, not a new one.

## Goals / Non-Goals

**Goals:**

- An unreadable membership never reads as a leave, on any tier, at any trigger.
- The skip/leave/run decision lives where a new tier cannot bypass it and a tier-neutral test can reach it.
- Every port that shapes what a member contributes is answered at the call site, not inherited from a
  default.
- `:test:world` composes the real cycle, so the `Skip` path becomes reachable from `:test:integration`.
- The record supersedes `event-link`'s single-tier phrasing by name, so the next tier author reads why it
  failed.

**Non-Goals:**

- Making the roots testable. They are `iosMain` platform glue; the goal is to shrink them, not cover them.
- The Konsist choke-point guard. This change unblocks it (see D5) but does not add it.
- Closing `:test:world`'s attestation-token gap in engine construction — real, separate.
- Changing what `UploadCycle`'s phases do. The 44 tests in `UploadCycleTest` cover behavior this change
  does not touch.
- Changing the Keychain accessibility posture. `AfterFirstUnlock` stays.

## Decisions

### D1: Create the choke point — `UploadCycle` performs the read

`UploadCycle` takes a required `readGate: () -> CycleGate` and calls it inside `run()`. The three-state
decision, the leave-side reconcile, and the direction gate then all live behind one public method:

```
  ┌─ UploadCycle.run() ───────────────────────────────────┐
  │  readGate()                                           │
  │    ├── Skip(detail)  ──▶ log, touch NOTHING, COMPLETED│  ← today: one root only
  │    ├── NotJoined     ──▶ reconcile(null), COMPLETED   │  ← today: copied ×3
  │    └── Run(eventId, contribution, saveToAlbum, host)  │
  │           ├── contribution == None ──▶ SKIPPED        │  ← already here (D1 of direction-gate)
  │           └── Since(cutoff) ──▶ phases 0–3            │
  └───────────────────────────────────────────────────────┘
```

This is the prior two fixes' shape applied to the gate that had no choke point to move to — so the change
**creates** the choke point rather than relocating the gate into an existing one.

_Alternative considered:_ copy `cycleGate(...)` into `UrlSessionUploadController`. Three lines, fixes the
live defect, ships today. Rejected: it is the fourth copy of a decision that has now caused three bugs, and
it leaves the next gate with the same trap. The direction-gate change made exactly this call and stated it:
*"sequencing the fix first means designing its parameter twice, since the refactor immediately subsumes
it."*

_Alternative considered:_ a shared `runConfiguredCycle(ports…)` function above `UploadCycle`. Rejected — it
would take ~12 parameters and pass ~12 down, a pass-through wrapper whose fair objection is "this is
`UploadCycle`". If the cycle is the choke point, the cycle should read.

### D2: `CycleGate` is the input type — not a new one

`CycleGate` already means *"what should this invocation do"*, is already tier-neutral, already lives in
`:capability:upload`, and is already tested (`CycleGateTest`, 6 tests). Its `configReadable: Boolean`
parameter is already the exact place the device-id probe is rolled in today (`UploadExtensionRoot.kt:257`).

`CycleGate.Run` grows to carry `contribution` and `saveToAlbum` alongside the `UploadConfig` it already
holds. All primitives plus `Contribution`, so **`:capability:upload` gains no dependency** — the cycle never
reads config, it consumes a decision.

_Alternative considered:_ a new `MembershipRead { Unreadable | None | Joined(…) }`. Rejected — it is
`CycleGate` with different spelling, and a parallel type beside an identical one is the duplication problem
in type form.

_Alternative considered:_ `cycleGate(read: ConfigRead, …)`, deleting the roll-up line from both roots.
Rejected — it buys three lines and costs `:capability:upload → :capability:config`, inverting the module's
stated scope for a translation.

### D3: What stays in the roots is translation, not decision

Each root keeps a lambda of roughly three lines:

```kotlin
readGate = {
    val read = configSource.read()
    val idReadable = runCatching { deviceId }.onFailure { if (it !is KeychainUnavailable) throw it }.isSuccess
    cycleGate(read !is ConfigRead.Unavailable && idReadable, read.joinedOrNull(), host)
}
```

The test for what may stay: **if the two roots' copies could ever correctly diverge, it is translation; if
divergence is always a bug, it is a decision and belongs in the cycle.** `read !is ConfigRead.Unavailable`
is a cast over this platform's storage type — a future tier could legitimately read membership from
somewhere that is not a Keychain. `runCatching { reconciler.reconcile(null) }.onFailure { log.w(…) }` is a
decision — that absence means leave, and that a failed clear is a warning — and no tier should ever answer
it differently. One did. That was the bug.

What is left after the shrink:

```
  before   process() ~150 lines · runCycle() ~65 lines     ← decisions, untested
  after    process() ~6 · runCycle() ~1 · gate lambda ~3   ← translation only
```

`process()` retains exactly the three irreducible tier differences: `runBlocking` (a synchronous OS
contract), `postLivenessNotification()` (a cross-process ding the app tier does not need because it writes
the ledger in-process), and the pending→`PROCESSING` requeue (the extension's substitute for the completion
callback it cannot have while not running).

### D4: Required ports — the rule the precedent already states, applied to the rest

`UploadCycle`'s parameters today:

```
REQUIRED         contribution                            ← made required by the direction-gate fix
                 reconcile                               ← made required by the lifecycle fix

DEFAULTED        onDiscovery = {}                        ← manifest: photos never enter the union
                 suppressedAssetIds = { emptySet() }     ← echo: downloaded photos re-upload
                 albumExcludedAssetIds = { emptySet() }  ← denylist: the WhatsApp album leaks
                 onBatchUploaded                         ← notify: other members are never told
```

Every parameter that has caused a shipped bug is required. Every parameter that has not, is not. The safety
is retrospective — each fix hardened the one argument that had just failed and stopped. The direction-gate
change wrote the general rule down (*"So: required, like `photoCutoff` and `reconcile` already are"*) and
applied it to the parameter in front of it.

All four become required. `UrlSessionUploadController`'s own `albumExcludedAssetIds = { emptySet() }`
default goes too — its comment argues the port *"must be supplied on both or the 18–26.0 tier would happily
upload the WhatsApp album the ≥26.1 tier refuses"*, while its signature permits omitting it. `SnapSyncRoot`
does pass it, so nothing is broken today: the property is held by diligence, which is what the same comment
says failed last time.

**Required does not mean "must have one."** A tier with no album support passes `{ emptySet() }`
explicitly — the same behavior, now a decision recorded at the call site rather than inherited in silence.
This is also what closes `:test:world`'s divergences: the world takes `onBatchUploaded`'s default today, so
`upload-completion-notify` has zero integration coverage.

_Alternative considered:_ make only `onDiscovery` required (the clearest invisible failure). Rejected —
it repeats exactly the retrospective pattern this decision exists to end.

### D5: The world composes; it does not mirror

`World.runUploadCycle()` re-implements the assembly today, under a comment that says so: *"composition
helpers (mirror `UploadExtensionRoot.process()`)"*. The mirror has been more correct than production —
before the lifecycle fix it reconciled while the real app tier did not — which is worse than being wrong,
because it stayed green while the defect shipped.

After D1 the world supplies a `readGate` over its config cell and calls the real `run()`. Two consequences:

- The world gains an `unreadable` lever, so `CycleGate.Skip` becomes **reachable** — today it is
  structurally inexpressible there, which is why no test covers the exact bug class that has now recurred
  three times.
- The world can no longer invent a cutoff. Its current `configCell.value?.minPhotoDate ?: DEFAULT_CUTOFF`
  fallback contradicts the project's central invariant that a cutoff is never absent; with the contribution
  arriving inside `CycleGate.Run`, there is nowhere to invent one.

This does not make the world call the real roots — impossible (see Context). It shrinks what the world
copies to the same residue the roots keep: a translation.

### D6: `Skip` carries its forensics

The extension's skip log is the only diagnostic for a state that is otherwise invisible on a device:

> `"skipping cycle — protected data unavailable (config status=$status, deviceId readable=$idReadable). NOT treating this as a leave; nothing minted, nothing reconciled, marker untouched."`

Moving the decision into the cycle would lose the `status` and `idReadable` detail — the cycle only knows
`Skip`. `CycleGate.Skip(detail: String)` lets the root supply the forensics and the cycle log them verbatim,
keeping one line in the device log rather than two across two files.

Cost: `Skip` stops being a `data object`, so `CycleGateTest`'s `assertEquals(CycleGate.Skip, …)` assertions
need a mechanical update.

_Alternative considered:_ the root logs, the cycle decides. Rejected — splits one event across two log lines
in two files, and `debug.log` is the canonical channel for exactly this failure.

## Risks / Trade-offs

- **Every `UploadCycle` construction site stops compiling** → Deliberate; the compile errors are the review,
  the same mechanism the prior two fixes used. Four sites: two roots, the world, the tests.
- **`UploadCycle` becomes long-lived, reversing part of the direction-gate fix's D2** → That decision made
  `photoCutoff` a plain `Contribution` value *"since the cycle is per-run, the lambda buys no freshness"*.
  With a long-lived cycle the contribution must be per-run again — but it returns as a *field of the gate
  result*, not a lambda parameter, which is cleaner than either. Confirm nothing else depended on per-run
  construction.
- **The change is a refactor of a class refactored hours earlier** → Merge conflicts and re-litigated
  decisions. Mitigated by touching the phases not at all; the diff is the constructor and the head of
  `run()`.
- **There may be no live defect at all** → The one plausible path (background `URLSession` relaunch before
  first unlock) is unproven; the other candidate is closed by Apple's `BGTaskScheduler` guarantee. This
  ships a structural closure, not an incident response, and the proposal says so. If a demonstrated defect
  is the bar, this change does not clear it and should be judged on D4 and the duplication instead.
- **The reachability question may be answerable and was not answered** → An `ios-url-session-upload` device
  test could settle it: reboot with an in-flight background upload, do not unlock, observe whether the app
  is relaunched to deliver session events. Not attempted here. If someone runs it and the answer is "no",
  D1's justification narrows to duplication alone — which is still an argument, but a weaker one.
- **Required ports are a wide blast radius for a narrow bug** → They are the part that closes the world's
  drift, and the part that is independent of everything else here. If the refactor stalls, D4 alone still
  pays.
- **The bug cannot be demonstrated by a test, and this is the finding rather than a gap** → The app tier's
  decision consumes `EventConfig?`. To fail, a test must supply an input meaning "unreadable"; **the type
  has no such value**. This is not code answering a question wrongly — it is a question that cannot be
  asked. A failing test is therefore impossible *before* the fix, and the first honest task is to record
  that rather than to fake one.

## Migration Plan

No data migration. No persisted format changes shape: the ledger, discovery cursor, join marker, and
Keychain items are untouched. `CycleGate` is a process-local decision type.

A device that performed a false leave under the defect has already paid for it (one extra re-join
reconciliation, which seeds from storage and re-uploads nothing). Nothing to repair.

Rollback is a revert.

## Open Questions

- **Does `onBatchUploaded` deserve required status on the same grounds as the other three?** A missing
  notify degrades rather than corrupts: other members still reconcile on their next foreground. It is
  included in D4 because it is the port `:test:world` silently omits, so requiring it is what buys
  `upload-completion-notify` its first integration coverage — but the invisible-failure argument is weaker
  for it than for the manifest or the denylist.
- **Should the device-id probe be part of `configReadable`, or its own gate input?** D2 folds it in, matching
  what the extension does today and what its comment argues (*"treat it exactly like an unreadable
  config"*). The counter is that `configReadable` then means "config **or identity** readable", which the
  name does not say. A rename (`preconditionsReadable`?) may be the honest fix, at the cost of churn in a
  6-test file.
- **Should `ios-app-shell`'s deferral requirement separate file protection from Keychain accessibility?**
  It requires background work reading *"the Keychain-backed device id and event config"* to defer on
  `UIApplication.isProtectedDataAvailable`. Those are two different conditions: file protection is
  unavailable whenever **locked**; an `AfterFirstUnlock` Keychain item is unreadable only **before first
  unlock**. Read literally, the requirement would defer every locked background wake and break background
  upload — so the upload path correctly ignores it, and the requirement quietly does not mean what it says.
  Out of scope here; it wants its own change.
- **Is `handleBackgroundUrlSession`'s download branch a real `ios-app-shell` violation?** It measures
  `protectedData.isAvailable()` into its log parameters and then acts regardless, and its download branch
  (`downloadJobs.adoptBackgroundEvents`) touches staging files — the case the gate genuinely exists for.
  The upload branch needs no gate (above). Separate concern, separate capability, not fixed here.
- **Does this unblock the Konsist guard cheaply enough to fold in later?** After D1 the roots no longer call
  `reconciler.reconcile(null)`, so *"every `.reconcile(` and `.createJob(` is inside `UploadCycle.kt`"*
  becomes writable — it is not today, because both roots legitimately make that call. Deferred here on
  scope, as the direction-gate change deferred it *"on scope, not on merit"*. Note the guard catches only
  the *extra*-decision direction; the *missing*-decision direction is what required parameters catch.
