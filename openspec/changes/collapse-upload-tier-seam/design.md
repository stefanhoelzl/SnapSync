## Context

Two upload tiers exist: OS-driven PhotoKit (iOS ≥26.1) and app-driven background `URLSession`
(18–26.0, or when forced). `upload-lifecycle` records that the tiers' mutual exclusion was originally
**structural** — only one producer was ever constructed — and that this was given up when the mechanism
choice became an input of *runtime* permission (the OS provably never invokes the extension under
`.limited`), which "no once-per-process construction decision can express". Both producers are now
composed on ≥26.1 and `UploadArm.selectedProducer()` picks by permission, with a 46,656-script
`:test:architecture` guard replacing the compile error.

`ComposedProducers.osDriven: UploadProducer?` therefore answers two questions with one nullable:

```
  null  ⟶  "this OS has no such mechanism"            real 18–26.0 device
  null  ⟶  "present, but this build must not run it"  forced build      ← conflated
```

The conflation is the defect. On a forced ≥26.1 build the mechanism is present and its registration
may survive from a prior process *or a prior install*, but nothing can reach its `stop()`.

An earlier design patched the nullable (a `stopOnly` list, a `Stoppable` supertype, a one-shot latch, a
sealed tri-state, an `osDrivenRunnable` boolean). Each step was locally justified; the total was
baroque. Separating the two questions removes all of it.

As of `retire-launch-env-triggers` landing, `resolveComposition` is already reduced to one OS fact
returning `UploadTier`, `LaunchDirectives` and the tier-force flag are gone, and `PhotoKitUploadProducer`
already reads its write's `Boolean` and `NSError` through a tested `registrationOutcome` classifier — so
the silent-write concern this design listed as out of scope is discharged, and the 3201-on-a-clean-device
case is classified rather than raised.

Three facts constrain any solution, all verified:

- `PhotoKitUploadProducer` calls a selector that **does not exist below iOS 26.1**; constructing it there
  is safe, calling either verb traps.
- `UrlSessionUploadController` is a **process-lifetime singleton**. `ios-url-session-upload`: a background
  `URLSession` identifier must stay stable for `handleEventsForBackgroundURLSession` re-adoption, and
  invalidation is terminal — an uncatchable ObjC `NSException` that aborts the process.
- `:app:ios` is wiring-only, gated by `detektAppShell` at `CyclomaticComplexMethod` threshold 2, with
  `KotlinShellGuardTest` pinning suppressions exactly in both directions. Any new branch there costs an
  argued pin.

## Goals / Non-Goals

**Goals:**

- One upload producer, resolved from `(OS facts, permission, override)`, re-resolved when an input changes.
- Restore the exactly-one-started invariant **structurally**, so it is a compile error rather than a
  guarded property.
- Make the forced build's deregistration a consequence of the resolution table rather than a special case.
- Remove the shell's tier branch: a future third tier is a new producer, not a new branch with six thunks
  (which `app/ios/CLAUDE.md` already describes as the intended shape for the iOS 27 async protocol).
- Never let a trigger reach nothing and strand an OS completion handler.

**Non-Goals:**

- The discarded `Boolean`/`NSError` at `PhotoKitUploadProducer.kt:79`. Independently correct, user-facing,
  and carried by `triggers-into-channel` so it does not wait on this.
- The `3202` disable→enable ritual. It exists for a *stale foreign-signed* record and is not reopened.
- The leave path's blanket repair. A similar redundancy argument exists (a rejoin re-baselines via the
  marker mismatch) but nothing runs after a leave, so the argument is weaker and the path is not ours.
- Building the tier-switch **endpoint**. This change provides the seam; the endpoint is rig-side.

## Decisions

### D1 — One resolved producer, not a selection over composed producers

`resolve(osFacts, permission, override) -> ProducerKind` is pure and exhaustively tested; a shell factory
maps kind to instance.

```
                    GRANTED          LIMITED          NOT_DETERMINED / DENIED
  ≥26.1 normal      PhotoKit         UrlSession*      Idle
  ≥26.1 forced      UrlSession*      UrlSession*      Idle
  18–26.0           UrlSession       UrlSession       Idle

  * on ≥26.1 the app-driven cell relinquishes the OS registration before pumping —
    a different producer from the same cell on 18–26.0, because OS version is an input.
```

The arm holds one reference and swaps stop-then-start. `ComposedProducers` and `selectedProducer()` are
deleted.

*Why this over the alternatives.* `stopOnly: List<Stoppable>` and the sealed `OsDriven` tri-state both
leave the conflated nullable in place and encode the forced build as "the odd one out" rather than as a
cell. A `runsUnder: Set<PermissionStatus>` on the mechanism deletes the exhaustive `when`, so a new
`PermissionStatus` selects nothing instead of failing the compile (`architecture-guards`, "Gates fail
closed on novelty"), and moves the LIMITED policy into `:app:ios`, which is exactly the placement
`upload-lifecycle`'s Purpose section exists to prevent. Passing `UploadTier` to the arm imports shell
vocabulary the core has never held and makes the arm re-derive selectability from an identity — the same
conflation, relocated.

*Why re-resolution works despite the singleton.* "Instantiate" means *obtain the producer for this cell*.
The factory caches where the platform demands a singleton, so switching away and back returns the same
`UrlSessionUploadController` and its background session is never invalidated.

### D2 — No empty cells: `Idle` is a producer, not `null`

Routing triggers through a nullable current producer strands OS completion handlers when nothing is
resolved. `OsReceipt`'s own KDoc: an unanswered handler "costs the app its future background wakes, which
is a worse failure than whatever threw." `Idle` declines every trigger while honouring the platform
contract — a deliberate collapse that names its consequence, per "Absence is never silent".

### D3 — Triggers live on the mechanism and are always delivered

`onForeground` / `onSilentPush` / `onBackgroundTask` / `onSelectionChanged` move onto the producer. The
caller never asks whether a mechanism is interested; it tells, and the mechanism declines.

*Why not capability negotiation* (a nullable thunk the caller branches on, as today): that is an
invoker-gate, and `upload-lifecycle` already rules on the shape — "an invoker-gate is only as sound as its
enumeration of invokers, and that enumeration is invalidated silently by a new tier or a new trigger."
Two of the six `LiveShell` thunks are already identical across tiers, which is the tell.

*No interface defaults.* A declining tier writes `override suspend fun onForeground() = Unit` with its
reason. `upload-lifecycle` forbids the permissive-default shape directly: "a permissive default on such a
port is an unstated answer."

*The arm's seam keeps exactly two verbs.* `UploadProducer` (start/stop, what the arm sees) and the trigger
seam are separate interfaces on one object, so "no destructive verb on the seam" is untouched.

### D4 — `OsReceipt` construction hoists to the entry point

Triggers become plain `suspend fun onX()`; no mechanism holds a raw handler, so none can forget to
release one. This is not a semantic change: `ReceiptDeadlines` already lives in `:domain ports/` and is
named **per OS wake** (`SILENT_PUSH`, `BACKGROUND_EVENTS`, `BACKGROUND_TASK`), and every current site —
in the controller and in the shell — uses those constants. The deadline was never the mechanism's to own.
The objection "a future tier may need longer" dissolves twice: the budget is the OS's and cannot be
granted, and the receipt already "bounds the hold, never the work."

### D5b — Both cells relinquish, symmetrically in structure and asymmetrically in content

**Found during implementation, by a test that already existed.** `UploadArmTest`'s
`granted_runs_the_os_driven_producer_where_composed` asserts the app-driven mechanism is *stopped* when
the OS-driven one starts. The first cut of this design did not stop it — the arm held one mechanism and
had no reason to touch another — and the test failed.

It was right to. The reasoning that justifies deregistering the OS-driven mechanism applies in **both**
directions: each mechanism leaves state the OS keeps on the app's behalf, and both kinds outlive the
process.

| left by | what survives | relinquishing it |
|---|---|---|
| OS-driven | the upload-job configuration record, keyed by bundle id — survives relaunch **and reinstall** | deregister, and *only* that |
| app-driven | in-flight background `URLSession` tasks and a submitted `BGProcessingTask` | its ordinary `stop()` |

Leaving the second in place is the same defect wearing the other hat: a process resolving the OS-driven
mechanism while a previous process's transfers are still completing has two writers over one ledger. So
both cells wrap: `RelinquishThenRun` is used in both directions, with *different* relinquish content —
narrow deregistration one way, an ordinary stop the other.

The hand-over is performed by the **incoming** cell, not by the arm stopping the outgoing one. That is
forced by D5: the correct teardown of the OS-driven mechanism depends on where control is going —
deregistration only when the app-driven mechanism is about to reconcile precisely, a full stop-with-repair
on a leave where nothing runs next. Only the incoming cell knows which, so the arm hands over rather than
carrying that table itself.

`stopAll()` is correspondingly *not* "stop what I hold": it stops every mechanism the factory can yield,
because a just-launched process holds `IdleUploadMechanism` and has started nothing while work it never
started may still be running on its behalf.

### D5 — The blanket repair does not fire on a switch to a precisely-reconciling tier

`clearRequested()` is ledger-wide and the discovery cursor is shared, so PhotoKit's `stop()` repairs reach
into state the app-driven tier is about to use. On the tier switch they are also **redundant**:
`ios-url-session-upload` requires that tier to reconcile stranded `REQUESTED` rows precisely from
`getAllTasks` and states it "SHALL NOT depend on `clearRequested`". The cursor clear exists only because
of the blanket clear, so it goes with it.

The repair stays exactly where it is load-bearing — the disable→enable **re-register**, after which
PhotoKit runs again and has no way to enumerate live jobs. The rule is conditioned on *what runs next*,
not on which tier is asking. This also stops a limited-access member's in-flight rows being wiped once per
process on a shipped path.

### D6 — `isUploadJobExtensionEnabled()` is logged, never branched on

The read exists (klib-verified, undeprecated). It is used for diagnostics and for the rig's
`/device/state`, and **no behaviour depends on it**. D5 removes the only reason we wanted a gate.

**Measured, and the reason is stronger than the one anticipated.** Probe on SE2 / iOS 26.6 (2026-08-21;
`PROBE-FINDINGS.md` on branch `probe-uploadjob-readback`): the read is **TCC-gated**. It reports `true`
for a configuration record that survived a delete-and-reinstall — but only once the app holds photo
access. In the same install, ungranted, that same live record reads `false`:

| photo grant | record present | read |
|---|---|---|
| GRANTED | yes | `true` |
| GRANTED | no | `false` |
| **NOT_DETERMINED** | **yes** | **`false`** ← the lie |
| NOT_DETERMINED | no | `false` |

So its `false` collapses *"there is no record"* with *"I am not allowed to see one"*, and the confounding
case is not the exotic differently-signed-build scenario that motivated this decision — it is **every
fresh install before the grant**, which is reachable on every device. A teardown skipped on that reading
would leave the extension registered and uploading behind the app-driven tier. Branching on this read is
now ruled out on evidence rather than on caution.

`LIMITED` was not exercised, so whether a partial grant is enough to make the read truthful is unmeasured
— which matters because the `LIMITED` cell is exactly where this change deregisters. Another reason no
behaviour may depend on it.

**The grant-independent oracle is the write, not the read.** `setUploadJobExtensionEnabled(false)` returns
`true` with no error when it found and deleted a record, and `false` with `PHPhotosError 3201` ("Unable to
find the configuration") when there was none. That answer is what the relinquish already produces as a
side effect of doing its job. (Caveat: 3201 was only ever observed *under* a grant; the write's behaviour
without one is unmeasured.)

**A read placed for diagnostics SHALL stay inside the ≥26.1 mechanism.** `isUploadJobExtensionEnabled` is
a 26.1 selector and the app deploys to min iOS 18, so an unconditional read in a composition root traps on
an 18–26.0 device. The probe's boot-banner read is explicitly marked not shippable for this reason. Any
diagnostic or rig-facing read must live where `setUploadJobExtensionEnabled` already lives — inside the
producer that is only constructed on the OS that has it.

### D7 — The exclusivity guard is retargeted, not deleted

The risk relocates and sharpens: a wrong resolver cell yields PhotoKit on an 18–26.0 device and the
selector traps, which is worse than two writers. And the arm gains mutable state (`current`), so sequence
bugs become *more* possible. So: an exhaustive cell test against the resolver (no cell yields a mechanism
this OS cannot run), plus the existing script machinery retargeted to "current always reflects the
resolved kind; every change is stop-then-start; no transition leaves a producer started."

### D8 — Absorb `resolveComposition` rather than sit beside it

`retire-launch-env-triggers` has landed and went further than expected: the sealed `CompositionMode` is
**deleted outright** (forge is its own Xcode target linking neither `:app:ios` nor the live graph), and
what remains is

```kotlin
fun resolveComposition(backgroundUploadSupported: Boolean): UploadTier
```

Mechanism resolution is a strict superset of that — same OS fact, plus permission, plus the override — so
`resolveComposition` and `UploadTier` are absorbed rather than reconciled.

`useBackgroundSession` / `isSimulator` are **gone from the tree entirely** (the simulator-transport
downgrade was deleted separately), so the "factory input" this decision previously described has no
subject. Nothing replaces it.

**This change must also amend that function's stated rationale, not merely its signature.** Its KDoc now
argues the absence of an override as a virtue — *"There is no developer input here: no launch-environment
variable, no build property, no runtime override. The tier a process runs is a function of the device it
runs on, and a reader of this function can see that at a glance rather than having to prove it."* Adding
the override makes that false. The claim is not reinstated by this change and is not smuggled past: the
override is dev-only, absent in every production build, and its presence is what a reader must be able to
see at a glance instead. Restoring the force is nonetheless the intended outcome, recorded in that
change's own decision record (`changes/archive/2026-08-24-retire-launch-env-triggers`, D14: *"Deleted here,
restored by the producer-resolution work as a runtime `forced` input. Not replaced in this change."*),
which also carries the cold-relaunch durability requirement.

### D10 — The override is a settable thunk the shipped binary cannot write

The override names a mechanism **kind**, is read fresh at every resolution, and reaches the resolver
through a thunk on the composition root that defaults to `{ null }`. The control channel's boot hook
replaces that thunk once, before the graph is forced; the channel then changes the pinned value live,
because the arm re-reads through the thunk on every transition.

**The safety property is structural.** The hook's source is not compiled into a build made without the
channel's build property, so in a shipped binary nothing can assign the thunk and it stays inert forever.
A production build is not *unlikely* to carry an override — it is *unable* to.

**This replaced a persistent design, and the replacement is the point rather than a simplification.** The
first version had production read a planted file from the shared container. That version could be handed
an override it never established, because the App-Group container **survives an application update**
though not a delete-and-reinstall — measured on device (SE2, iOS 26.6, 2026-08-25: a membership joined,
the same IPA installed over the app without deleting, the config intact after relaunch). Defending it
needed a process-scoping rule (`processIdentifier` plus a monotonic `systemUptime` check), which could
only *bound* the hazard: same-boot identifier reuse across an install remained, and the design carried a
"log whenever honoured" requirement so the residual would at least be diagnosable. All of that —
the plant type, the codec, the rule, its tests, the honour-log, and the residual — is deleted, because
where the writer cannot exist in the binary that must not honour the value, there is nothing to refuse.

**Non-durable, deliberately, and revisitable without touching production.** The override does not survive
process death. Both measurements this change owes happen inside one process, so neither needs durability;
the task that did need it existed only to verify durability itself, and is deleted with it. What
non-durability costs is a long test session on a device: the app-driven tier is driven by background-task
and background-session relaunches, so after a wake the next *foreground* re-resolves without the pin. If
that becomes painful, durability is two lines of `NSUserDefaults` **inside the channel** — production
still sees only the thunk, so the durable/non-durable choice never crosses the seam.

**The cost, stated.** The composition root carries one assignable field whose only writer does not ship.
`module-architecture` asks that a build-time-only module "contribute its own call site rather than making
a shell carry a permanent seam", and this is a seam, smaller than the file version's (a production class
reading a dev-only path) but a seam. It is accepted here because the alternative it replaced was a
*larger* seam with a residual hazard attached, and because the field is inert by construction rather than
by discipline.

*Rejected — a baked build constant* (an xcconfig value read from `Info.plist`, like the compile-time
upload host). It carries no mutable production state at all and is the most consistent with where the rest
of the dev surface landed. It was not taken because switching mechanisms would then require a rebuild and
reinstall per switch, and the lever's whole purpose is exercising a tier across a device session.

### D12 — Why the settable field is not what `add-rig-control-channel` D3 refused

Raised by `rig-simulator-host`, and argued here rather than assumed, because two changes reading the same
prior decision in opposite ways is worse than either reading.

**What D3 actually rejected** (`changes/archive/2026-08-09-add-rig-control-channel`, verbatim): *"a
`startRig { app }` line in `SnapSyncRoot` with a swapped-in `= Unit` stub — works, depends on no
experimental behaviour, but puts **test-infra vocabulary** and a **permanent dead file** in the production
composition root."*

Neither objection applies here:

- **No test-infra vocabulary.** The field is `uploadMechanismOverrideSource: () -> UploadMechanism?`. It
  names no test infrastructure; `UploadMechanism` is the resolver's own production type, and a development
  override is a production **contract** in this capability, not a test concept smuggled into the root. The
  rejected line named `startRig` — the rig itself — in the shell.
- **No permanent dead file.** The default is an inline `{ null }`, not a stub implementation that must
  exist as a file in production builds. Nothing is swapped; nothing is dead.

**What D3 accepted, and where this genuinely goes further.** D3's production diff was *"two fields widened
`private` → `internal`"* so the hook could **read** them. This adds a third the hook **writes**. That is a
real difference and worth stating plainly rather than hiding inside the two objections above: reading lets
test equipment observe production state, writing lets it influence production behaviour.

The justification is that the behaviour it influences is **already specified as production behaviour**.
`upload-lifecycle` requires resolution to take an override; this field is how the composition root supplies
it, and the control channel being its only supplier is the same relationship the build has to
`BACKGROUND_UPLOAD_URL_BASE` — a production-specified value with exactly one source, absent when that
source does not run. The field is inert in a shipped binary **by construction**: its only assigner is
compiled out, so it is not "unlikely to be set", it is unable to be.

**Why the compile-time answer that solved the identity case is unavailable here.** `add-simulator-rig-host`
replaced its equivalent with a target swap (`iosArm64Main` vs `iosSimulatorArm64Main`), which works because
simulator-versus-device **is** a compilation axis that already exists in the build. Forcing an upload
mechanism on a *device* has no such axis: the same binary, on the same target, must be able to run either
mechanism. The nearest compile-time equivalent is a baked build constant, which this design rejected on
ergonomics (a rebuild and reinstall per switch, for a lever whose purpose is exercising a tier across a
device session) — not on principle. A reader who prefers that trade should read *Rejected — a baked build
constant* in D10 as the live alternative.

## Risks / Trade-offs

- **A wrong resolver cell calls a trapping selector on 18–26.0 → process abort.** Strictly worse than the
  failure it replaces. → The exhaustive cell guard of D7 is the mitigation, and it is the reason that
  guard must not simply be deleted as redundant.
- **`current` is new mutable state in a `feature/` class.** → It is a coordination primitive, not
  authority: derived from permission on any relaunch, so the kill-test holds. Covered by the retargeted
  script guard.
- **A future tier silently forgets a trigger answer.** → No interface defaults (D3); every tier states
  every answer at its definition site, reviewable in the diff.
- **Deadline values drift during the `OsReceipt` hoist.** → Move the named constants, never the literals;
  `PlatformIdentifierTest` no longer pins any of them — `URL_SESSION_EVENTS` was renamed
  `BACKGROUND_EVENTS` and discharged from the deferred list, which now stands at zero, so nothing outside
  the constants themselves guards a drift in their values.
- **Ordering dependency on `triggers-into-channel`.** Landing first would build on `LaunchDirectives` and
  `forceUrlSessionUpload`, both being deleted. → Deltas here are written against the post-that-change
  tree; that change lands first.
- **The window this change owes.** `SNAPSYNC_FORCE_URLSESSION_UPLOAD` is deleted on the bet that a rig
  endpoint replaces it, and the endpoint needs this change's `forced` seam. → Bounded: a `.limited` grant
  still exercises the pump, scheduler, background `URLSession`, staging and ledger writing on a ≥26.1
  device. Only the full-grant discovery **walk** is unexercisable until both land.
- **Production Kotlin reads a dev-only fact.** The compile-time contained alternative is unavailable
  (D10), so production carries the read. → It is inert by construction: nothing in a production build ever
  writes it, so the override is always absent and the branch unreachable. Absence has exactly one meaning.
- **A stale override could outlive the build that planted it.** An App-Group container survives an app
  *update* even though it dies on delete-and-reinstall, so a value planted by a rig build could in
  principle be read by a later non-rig build on the same device. → Needs settling with
  `rig-simulator-host` before implementation: confirm whether installing a differently-signed build over
  a rig build clears the container, or make the plant self-invalidating.
- **The trigger seam grows from two verbs to two seams.** → Kept as separate interfaces on one object so
  `upload-lifecycle`'s "exactly two verbs, no destructive verb" survives for the arm's seam.

## Migration Plan

1. `triggers-into-channel` lands: `LaunchDirectives` deleted, forge becomes its own Xcode target,
   `CompositionMode` reduced to one case, and the `setEnabled` `Boolean`/`NSError` fix carried.
2. This change lands: resolution replaces selection, triggers move to the mechanism, `CompositionMode` and
   `UploadTier` are deleted, the repair carve-out is applied.
3. The rig's tier endpoint drives `forced`, closing the window.

Rollback is per-step: each is an independent PR, and step 2 does not change any durable on-device state —
the ledger, the discovery cursor and the OS registration record are all left in whatever state the prior
build left them, and the resolution table converges on the next transition.

## Open Questions

- **Can a stale override outlive the build that planted it?** See Risks. The narrow path is an app
  *update* rather than a reinstall; it must be closed or shown to be impossible before this ships.
- **Must the extension process observe the same override?** Undesigned, and `rig-simulator-host` declines
  to commit on a later change's behalf. The requirement here is only *do not preclude it* — an App-Group
  location is shared with the appex by construction, so nothing is foreclosed.

- ~~**Is the push receiver's `GRANTED`-exactly guard over-broad?**~~ **Answered: no — it is required by
  name.** `limited-photo-access`'s "No autonomous library reads under a limited grant" enumerates exactly
  three things that must skip their `PHAsset`-fetching work under a partial grant, and *"the upload half
  of the silent-push fan-out"* is one of them. The same requirement fixes reads at "exactly two moments
  and no others" — a cold-foreground baseline and a selection-change emission — and a silent push is
  neither. My speculation that the guard was over-broad because the app-driven cycle consumes the
  selection snapshot rather than walking was wrong: the discipline is stated against the **trigger**, not
  against whether a particular mechanism's discovery walks. Relocating it into the mechanism therefore
  preserves it exactly — the mechanism's `onSilentPush` declines under `LIMITED` — and widens nothing.
- ~~**Does `isUploadJobExtensionEnabled()` report `true` after a reinstall?**~~ **Answered** — yes, but
  only under a usable photo grant; see D6. The differently-signed-record case and `LIMITED` remain
  unmeasured, and `PHPhotosError 3202` was never reproduced, so `ios-photokit-upload`'s premise that a
  bare `enable(true)` *fails* on a stale record is still unverified by measurement (the disable→enable
  ritual was confirmed to handle a surviving record correctly, which is what the requirement exists for).
- **Does the leading disable of the disable→enable ritual need a benign-3201 carve-out?** The probe shows
  that disable returning `false` + 3201 is the **normal** outcome on a device with no record — i.e. on
  every fresh install. It is expected, not a fault. Whoever lands the `setEnabled` error reporting must
  not raise it as an `Error` (and therefore a crash-reporting event) in that case. Carried by
  `triggers-into-channel`; flagged to that session.
