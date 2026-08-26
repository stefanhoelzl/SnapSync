## Context

`add-simulator-rig-host` named this change as the next in its plan and scoped it as *"an extension-shaped
second process"* — the reading being that the simulator's problem was **process** shape: the OS never
invokes an appex there, so give the host a second process that runs the extension's composition.

Two things have since falsified that framing.

**The transport gap closed.** `bind-transport-session-by-target` actualized `transferSessionConfiguration`
per compilation target, and uploads and downloads now work on this host — two members of one event, both
directions, measured 2026-08-26. The `ios-simulator` skill records it. What remains missing is only the
OS-driven tier, which still *resolves* under a full grant on a ≥26.1 simulator and then does nothing,
because no appex is ever invoked.

**The probe says the wall is one subsystem, not the process boundary** (`PROBE-FINDINGS.md`). Running the
real `UploadExtensionRoot` inside the app process cleared nine seams — the shared `uploadCore`, the entry
gate, device identity through the App-Group file store, the re-join reconcile over real HTTP, real PhotoKit
discovery, the real selection policy, the engine's decision — and then died at `createJob`, inside Apple's
own frame:

```
-[PHAssetResourceUploadJobChangeRequest setUploadJobConfiguration:]
  → +[NSArray arrayWithObjects:count:]
  → NSInvalidArgumentException: attempt to insert nil object from objects[0]
```

preceded by a registration that was refused outright: `setUploadJobExtensionEnabled(true)` → `false`,
`PHPhotosErrorDomain:-1`, under a **full** grant on a clean device.

So a second process would have bought only cross-process SQLite lock arbitration — and would not have
helped with the wall at all, since a second rig bundle is no more the *registered extension* than the app
is. The gap is four job verbs plus the registration record, and the cure is to substitute them.

Constraints this design works inside:

- **Reaching `createJob` on a simulator is fatal**, not an error. Containment must be by compilation, not
  by a runtime choice.
- `:app:ios` is **wiring-only**, detekt-gated at `CyclomaticComplexMethod` threshold 2. It may hold no
  conditional — which shapes both the registration port and the log-line fix.
- `:test:rig` is contained at **compile time** (`-Psnapsync.rig=true`) and has **no tests**, so behaviour
  added there is uncovered by construction. Minimising it is a design constraint, not a preference.
- The device path must not change. `ios-photokit-upload`'s expected-code enumeration is *"closed and
  measured"* and widening it needs a **device** measurement on an **ordinary path**; ours is neither.

## Goals / Non-Goals

**Goals:**

- Run the OS-driven tier's real cycle — the same `uploadCore` the appex assembles — on a simulator, against
  a real ledger, real PhotoKit discovery, and a real backend, with the mechanism resolving `photokit`
  rather than pinned away.
- Make reachable, for the first time on any host, the cycle's adjudication of job states it rarely sees:
  `CANCELLED`, `REGISTERED`, and the `else → PENDING` arm whose own KDoc calls it *"a guess"*.
- Make the registration ritual and its `stop()` repair testable — including the stale-record `3202` path
  and the fire-and-forget race the repair was written to fix.
- Give the edge-URL builder a real end-to-end oracle: the exact composed URL, with the bytes PhotoKit
  hands back, under a real token, answered by a real backend.
- Add no runtime seam to a shipped binary and no second composition.

**Non-Goals:**

- **A second process, or any new Xcode target or bundle.** D1.
- **Cross-process ledger lock arbitration**, and process death mid-transaction. Device-only, and thin
  besides — the identity half of that story is a simulator-only `SecureStore` binding, not shipped code.
- **OS scheduling of `process()`**, the appex Swift shell and its `processingResultRawValue` handoff, and
  the appex memory cap. Device-only, unchanged.
- **Exercising `IosPhotoKitUploadPlatform`'s OS-effect body** — `performChangesAndWait`, the real
  acknowledge, the fetch loop. Substituted here by construction; see D12 for the coverage division.
- **Changing device behaviour in any way**, including the severity or classification of a
  `PHPhotosErrorDomain:-1` on a real phone.
- **`PermissionStatus.LIMITED` on a simulator.** Still not known to be grantable headlessly; and the
  registration is refused under a partial grant regardless (`3311`, both directions, measured).

## Decisions

### D1. No second process; the rig calls the real extension root in the app process

*Rejected: a rig-only app target linking `SnapSyncUploadKit`, modelled on `SnapSyncForge`.*

The second process was scoped to buy four cross-process properties. Three evaporate on inspection:

| property | what a second process would add here |
|---|---|
| device identity agreement | nothing — the id resolves through an App-Group **file** on this target, not the Keychain; the skill already says identity here is *"a precondition, not coverage"* |
| config file of record | nothing — same file, same `FileBackedConfigStore`, same read path |
| ledger | little — calling the real root already opens a **second** `LedgerStore` over one App-Group SQLite file |
| cross-process lock arbitration | this alone, and it is one property |

And on the axis that actually blocks the tier, a second process buys nothing: it would be
`app.snapsync.exthost`, not the registered extension, so whatever refuses the app refuses it identically.

Against that: a new Xcode target, bundle id, entitlements plist, `Deployment.plist` resource, signing step,
and a second Swift shell — for one property.

### D2. The substituted seam is the OS's upload-job subsystem, cut at the subsystem's edge

The rig substitutes `setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`, `fetchJobsWithAction`,
and job creation/retry/acknowledge — and nothing else in PhotoKit. Assets, resources, change tokens and
albums stay real, and demonstrably work on this host.

*Rejected: substituting only the four `BackgroundTransfer` job verbs and special-casing the registration.*
Cutting inside the subsystem is arbitrary; the registration record is OS state exactly as the job queue is.
Leaving it out also leaves an unfixable `Error` on every join (see D8).

`BackgroundTransfer.discoverResources` is **delegated to the real `IosDiscovery`**, exactly as
`IosPhotoKitUploadPlatform` delegates it. A wholesale substitution would discard the change-token walk and
the selection policy's real inputs, which are among the most valuable things this host offers.

### D3. The rig invokes `UploadExtensionRoot` verbatim, not a copy of its wiring

`:app:ios` gains `implementation(project(":app:ios:extension"))` **only** under `-Psnapsync.rig=true`, the
same containment shape the rig hook already uses.

One obstacle, and it is exactly one: Kermit's writer list is process-global and **both** roots set it in
their `init` (`SnapSyncRoot.kt:141`, `UploadExtensionRoot.kt:79`). Touching the extension root would
redirect the app's own log into `ext-debug.log` and silence `/logs`. The rig snapshots
`Logger.config.logWriterList` and restores it around the call — `Logger.addLogWriter` is already used for
precisely this reason at `SentryDiagnosticsReporter.kt:131`. The cycle's own lines still land in
`ext-debug.log`, which is where they belong; `IosDeviceLogSource` already serves that file as
`Process.EXTENSION`.

`:app:ios:extension`'s build script warns that the split frameworks exist so *"the two process binaries
never both statically pull `:domain:engine` into one image"*. That concerns two Xcode static frameworks in
one bundle; a Gradle module dependency folds it into `SnapSyncKit` instead. **Settled by a compile**, per
the law: `:app:ios:linkDebugFrameworkIosSimulatorArm64` succeeds with the module on the path.

### D4. The job queue lives in the caller, not in the app

The trigger takes the finished jobs as **input** and returns the newly created jobs as **output**. The
substitute holds no queue and no memory between invocations.

This is not a shortcut around durability — it is where the durability already is. PhotoKit's queue is
external to the appex; `process()` is handed the current job sets and hands back new ones. Moving the book
to the caller reproduces that topology rather than inventing one.

It also keeps `:test:rig` inside its own stated posture — *"every surface here is a mechanical projection
of a contract specified elsewhere … so there is no second way-to-drive that can rot or lie."* A durable rig
queue would be real behaviour in the module with no tests, and a failing scenario would not say whose bug
it was.

*Consequence, and the one thing this costs:* a finished job arriving as JSON carries no `PHAssetResource`,
and `drainTerminals` must return *"retry-spent failures whose resource is still live"* for the cycle to
re-create them. Without recovery the cycle silently takes the legal "resource no longer live" branch. So
the substitute recovers the resource **from the key** (`<assetId>-<role>.<ext>`, `_`→`/`, then
`fetchAssetsWithLocalIdentifiers` → `assetResourcesForAsset`). The perform verb needs the same recovery to
read bytes, so it is built once and used twice. Key-derived identity is already the established idiom here:
`photoKitContentType` reads the type off the destination's stored headers rather than the resource, and the
retried `Resource` is one *"the cycle rebuilds from the key alone"*.

### D5. Success is transferred for real; failure is forgeable

`POST /device/upload-jobs/perform` reads the resource bytes and performs an actual PUT against the
destination and headers the cycle composed. A forced failure skips the PUT and names an `UploadError`.

The asymmetry is not a preference; it falls out of what each half is worth. Declaring success without
moving bytes is what `:test:world` already does, in-memory, faster, and on JVM too — zero marginal value
here. A **real** transfer is the entire marginal value of this host: it is the only thing that would prove
a URL the edge-URL builder composes is one the backend accepts, with the bytes PhotoKit yields, under a
real token. Forging failure, conversely, costs nothing and buys determinism for the retry chain.

The transfer must not happen inside `createJob`. There are two distinct failure doors — `CreateResult`
(cap, malformed destination) and the transfer outcome (`fetchRetryJobs` → single free retry →
`drainTerminals` records `FAILED` and hands back for re-creation) — and collapsing them routes every
transfer failure through the creation door, making the engine's retry chain undrivable.
`FakeBackgroundTransfer` is shaped the same way for the same reason.

The byte read follows `IosUrlSessionUploadPlatform.stageResource`'s technique —
`PHAssetResourceManager.writeDataForAssetResource(resource, toFile:)` then an upload task from that file —
but **not** its adapter: that class *is* the app-driven tier's mechanism, and routing the OS-driven tier's
test transport through it would launder the thing under test through its alternative.

### D6. The substitution is bound by compilation target

`iosArm64` gets the real PhotoKit implementations; `iosSimulatorArm64` gets the substitute. A device binary
contains **no route** to it — *"contained by compilation, not by a runtime check"*.

Here that law is load-bearing rather than tidy: reaching `createJob` on a simulator does not return an
error, it raises an uncatchable ObjC exception and terminates the process. A runtime switch that could be
taken wrongly would kill the app under test.

Two precedents on this exact host, both for measured simulator platform facts: `deviceIdPrimaryStore()` and
`transferSessionConfiguration`.

### D7. The transfer is chosen by the target, not passed by the rig

`:adapter:ios:ext-safe` declares `uploadJobQueue(...): BackgroundTransfer` as an `expect`, with `iosArm64`
binding `IosPhotoKitUploadPlatform` and `iosSimulatorArm64` binding the substitute.
`UploadExtensionRoot` calls it instead of constructing the PhotoKit adapter directly, and is otherwise
untouched.

**This corrects an earlier version of this decision that contradicted D3.** That version said "production
passes `IosPhotoKitUploadPlatform`; the rig passes the substitute" — which cannot coexist with invoking the
root verbatim, because the root builds its own transfer and the cycle closes over it. For the rig to
*pass* a transfer it would have to call `uploadCore` itself, and then it is not invoking the root at all.
The two spec deltas were already written the other way (*"seams whose implementation is chosen by
compilation target"*, and a guard asserting each target's **actuals**), so this decision was the outlier.

Choosing at the target keeps D3 whole: the rig enters the real root, so the root's `init`, its
process-lifetime singletons, its `runBlocking`, its `requeueWhilePending` requeue rule and its
`ext-debug.log` destination are all exercised rather than reimplemented.

*Rejected: extracting the whole cycle body — `runUploadCycle(scope, transfer)` in `:app:ios:extension` —
and calling it from the root with PhotoKit and from the rig with the substitute.* Nothing would be
duplicated, and it removes the logger hijack outright (a top-level function does not initialise the
object). But the rig would then never enter the root, so the root's `init`, its boot banner and its log
destination go unexercised on the only host that can exercise them — and both spec deltas and the binding
gate would have to be rewritten away from `expect`/`actual` for no gain the first option does not already
have.

*Rejected: a settable transfer on the root with a production default.* A runtime seam in shipped code whose
safety rests on nothing ever setting it — the shape `keep-the-target-bound-identity` rejected for the
identity fallback, and for the same reason.

The cost is honest and worth stating: the simulator substitute lives in a **shipped module**. It lives only
in `iosSimulatorArm64Main`, a source set no shipped binary links — the same seat, and the same argument,
as `AppGroupFileSecureStore`, whose own KDoc already calls it test equipment. The objection to that seat
was its size, not its principle, and a drift between what the rig adjudicates and what the device
adjudicates is the worse cost.

Because the substitution is a property of the target rather than of the caller, `/os/photokit-ext/…` also
works on a **device**, where it drives the real OS job queue. Forcing a cycle on demand there — rather than
waiting for the OS to schedule one — is a capability the SE2 did not have.

### D8. Registration moves behind a port

A port in `:domain` `ports/` returning `RegistrationOutcome`, with the PhotoKit adapter in
`:adapter:ios:app-only` (only the app process registers). `PhotoKitUploadProducer` takes the port.

Three reasons, and the first is the ports law: `PhotoKitUploadProducer` makes raw `PHPhotoLibrary` calls
from `:app:ios`, which is wiring-only. *"Anything touching an external system goes through a port interface
in `ports/`, named for the need; adapters implement, named for technology, placed by linkage."*

Second, it dissolves the `-1` problem without touching anything device-facing. With the enable answered by
the rig there is no error to classify, so the closed and measured expected-code enumeration is never
widened and a `-1` on a device stays exactly as loud as today. The alternative — leaving the failure loud —
puts a permanent `Error` on every join on this host, which destroys the *"ZERO Error/Assert lines"* health
assertion `add-simulator-rig-host` used as its own acceptance criterion and which scripted scenarios want
most. It also inverts the requirement's own anti-noise reasoning, which refuses to raise on `3201` because
that would *"bury the signal this requirement exists to surface in noise the requirement itself created"*.

*Rejected: adding `-1` to the expected-code enumeration.* Forbidden by the requirement on its own terms —
widening needs a **device** measurement on an **ordinary path**. And `-1` is generic: demoting it globally
would blind a real phone to a real terminal failure.

*Rejected: a per-target binding that simply skips the registration.* Silence where a statement is
available, and it forgoes the third reason below.

Third — and this is the payment — the registration contract becomes exercisable for the first time
anywhere. **The port alone does not achieve that, and an earlier version of this decision said it did.**
The mechanism itself sat in `:app:ios`, which is wiring-only and untested by rule, so nothing could reach
it there however clean its collaborators became. Two further moves close the gap, and both stand on their
own merits:

- its **last raw platform call** goes — `stop()` open-coded an `NSUserDefaults.removeObjectForKey` against
  the very key `DiscoveryStore.clearToken()` already owns, so the discovery cursor had two writers and one
  of them was invisible to every fake, harness and test;
- with that gone the class is platform-free, so it **moves to `:domain` `feature/upload`**, beside
  `UploadArm`, `RelinquishThenRun`, `clearRequestedOffMain` and `BackgroundUploadPump` — the app-driven
  tier's sibling, which has lived there all along — and is **renamed `OsDrivenUploadMechanism`**. Named for
  the need, because the platform-free core is no place for a technology name: what it is is "the mechanism
  where the OS does the uploading", and PhotoKit is how iOS spells that. `UploadMechanism.PHOTOKIT` keeps
  the technology name where it belongs, in the resolver's vocabulary.

What that buys: the stale-record `3202` path the disable→enable ritual exists to fix; `stop()`'s repair, which
must clear orphaned `REQUESTED` rows and reset the discovery cursor, and whose KDoc records a real race
(*"a fire-and-forget clear raced the immediate re-enable and could delete the re-enabled extension's fresh
rows"*) that has no test; the `3311` refusals in both directions; and every arm of `registrationOutcome`.
All of it now runs on **JVM and the simulator**, in `:domain`'s own `commonTest`.

*Asymmetry with D4, stated deliberately:* the job queue can be stateless because the OS hands job sets in
per invocation. Registration cannot — the producer calls it mid-join with no caller in the loop, and
`/device/state` reads it at arbitrary times. So the rig holds a boolean plus a forced-failure code. That is
two fields, not a queue, and it matches the record's real nature: durable and external, surviving app
delete/reinstall and reboot.

### D9. `/os` is prefixed by composition root, and the guard derives per group

`/os/app/<member>` and `/os/photokit-ext/<member>`. Route leaves stay equal to the `@PlatformEntry` member
names, and `RigControlChannelTest` derives per root **group**: each root file's marked members must equal
the wired-plus-excluded keys within its own group.

Grouping is what makes a name collision across roots structurally impossible rather than something to
assert about: a flat namespace comparing sets would silently dedupe two identically-named entries and drop
one from the inventory. It also lets `onTerminate` read unambiguously as the extension's without renaming
the Kotlin member.

Both `@PlatformEntry` members of `UploadExtensionRoot` are wired: `processRawValue` and `onTerminate`.

*Rejected: leaving the app's routes unprefixed and adding only the new group.* Zero churn, but "unprefixed
means the app" becomes a convention the guard cannot check, and the guard would need a special case for the
one group that has no prefix.

The guard's current scoping reason — *"the rig runs in the app process, so the extension root's entry
points are not reachable from it and are not its to account for"* — is **falsified** by this change. It is
replaced, not reworded.

### D10. The invocation runs on its own serial thread, never the main lane

Every other trigger is invoked on `Dispatchers.Main`, because *"Swift calls entry points from the main
thread; so does the rig"*. That reasoning does not transfer. The lane law is explicit: *"In the extension
process there is no UI and no main lane: `process()` is synchronous by the OS's contract and runs under
`runBlocking` on the OS-invoked thread."* Running that `runBlocking` on the live app's main thread would
freeze the UI for the whole cycle and can deadlock on anything the cycle needs from main. A dedicated
single thread is the closest honest analogue: serial, because core code relies on that for mutual
exclusion, and not the UI's.

### D11. The invocation is refused unless resolution yields `photokit`

The trigger reads `resolveUploadMechanism` over the live inputs and refuses with the resolved mechanism
named when it is `url_session` — because there the app's own arm holds a live `LedgerWriter` and the
invoked cycle would be a second one over the same App-Group ledger, breaching `sync-ledger`'s
single-record-writer invariant. Under `photokit`, `PhotoKitUploadProducer` writes no ledger rows at all, so
the invoked cycle genuinely is the sole writer — the shipped division of labour exactly.

*Rejected: an operator rule in the skill.* Other rig hazards are handled that way, but a violated ledger
invariant is silent, and this one has already been expensive once.

### D12. The terminal-job adjudication moves into `PhotoKitJobMapping`

`drainTerminals` decides `SUCCEEDED → UPLOADED` else `FAILED`, calls `markTerminal`, and emits for
re-creation only when the failure is retry-spent **and** the resource is still live. Those eight lines sit
in the OS-effect body, although that file's KDoc already claims *"every mapping and per-job decision now
lives in `PhotoKitJobMapping.kt` … What remains here is OS effect."* The substitute would otherwise
re-implement them in the module with no tests, where a drift between what the rig adjudicates and what the
device adjudicates would make every scenario quietly lie.

This yields a clean three-layer coverage division with no gap:

| layer | instrument |
|---|---|
| `PhotoKitJobMapping` — classify, state map, content-type, `createResultFor`, error map | unit tests (JVM + simulator) |
| the cycle's adjudication of those states — ledger, re-create, retry chain, cursor, promotion, album, notify, manifest, `CycleResult` | **this host** (new) |
| OS effect — `performChangesAndWait`, real acknowledge, real creation, OS scheduling | device only |

### D13. The unearned post-registration success line is deleted

`start()` logs `"background-upload extension re-registered (disable→enable, cleared REQUESTED)"`
unconditionally, immediately after `RegistrationOutcome` may have classified the enable as **failed**.
`Applied(enabling = true)` already logs `"extension enable succeeded"`, and `clearRequestedOffMain` already
reports its own work — so both halves of the claim are made twice, once conditionally and once not.

The fix is deletion rather than a conditional, and the shell gate is what forces that: `:app:ios` is
detekt-gated at complexity 2, so `if (outcome.applied) log.i { … }` is a decision it may not hold. Letting
the outcome speak is precisely what `RegistrationOutcome` carrying its own `severity` and `message` was
built for — *"the shell renders without deciding anything."* The bug is the shell deciding, by asserting.

It stops being incidental once D8 lands: the first scenario anyone writes — a stale record refusing the
enable — would print `extension enable FAILED` followed by `extension re-registered`.

### D14. The wire vocabulary is the already-pinned platform vocabulary

A finished job is stated by the caller as an action (`retry` | `acknowledge`), a `PhotoKitJobState`
(`SUCCEEDED`, `FAILED`, `CANCELLED`, `PENDING`, `REGISTERED`), and an `UploadError` where one applies. The
caller supplies the retry disposition explicitly rather than the substitute inferring it, which is what
keeps the substitute memory-free.

Nothing is invented: `PlatformVocabularyPinTest` already pins those five SDK constants **and their values**
against the platform klib, so a case Apple adds fails the Kotlin bump. The caller is playing the OS, so it
speaks the OS's terms — mechanically pinned ones.

### D15. The route migration ships inside this change

*Considered: landing `/os/app/…` as a separate mechanical PR first.* It is a pure rename touching one guard
and two skills, and it would keep the naming churn out of a larger diff. Kept together because the prefix
exists **only** because `/os` gains a second root, which is this change — split out, it would arrive as a
rename with no stated reason, and the guard's per-group derivation would have nothing to group.

## Risks / Trade-offs

- **The `-1` refusal is n=1** — one runtime (iOS 26.5), one signing form → *No other signing form exists on
  this host: a simulator refuses `keychain-access-groups` and every other provisionable entitlement
  (measured), so ad-hoc with App-Group-only is the only buildable configuration, which makes the claim
  co-extensive with the measurement. It inherits the same expiry as the other PhotoKit facts: re-measure at
  the next iOS major.

- **The nil in `setUploadJobConfiguration:` is inferred, not measured, to be the missing registration
  record** → Recorded as an inference in `PROBE-FINDINGS.md`. It does not change what this design does: the
  subsystem is substituted whole either way.

- **Whether creation is additionally restricted to the registered appex's process is unmeasured** → The
  probe ran in the app process on this host and never in an appex. It cannot change the outcome here, and
  it is stated rather than resolved.

- **Key → `PHAssetResource` recovery is new code doing something no shipped code does** → Byte reading
  itself is proven on this host (the app-driven tier's uploads were measured working 2026-08-26) and
  `stageResource` is the reference. Recovery is a fetch by local identifier, using APIs already in use.

- **The rig's transfers use a *default* `NSURLSession`, so they die with the process** — a background
  session moves nothing on a simulator (`nsurlsessiond` rejects the bundle id as `(null)`), while the OS's
  own queue genuinely survives → Named in the skill beside the existing
  `handleBackgroundUrlSession` expiry caveat, in the same *"this is the host, not a fault"* voice. Not
  fixable on this host.

- **`IosPhotoKitUploadPlatform`'s OS-effect body is substituted away, so this host does not exercise it** →
  By design; D12's table says which instrument covers what. The mappings it delegates to are unit-tested,
  and the OS effect was already device-only by that class's own KDoc.

- **The route migration moves every existing `/os/<name>`** → Bounded: dev infrastructure with no spec, one
  documented consumer (`rig-channel`), and `RunbookSkillsTest` fails loudly if the skills fall behind. No
  installed base, so no alias table.

- **`:test:rig` gains more untested behaviour** → Minimised by D4 (stateless jobs), D7 (one shared port
  bundle) and D12 (adjudication extracted to a tested file). What remains is the request/response
  projection and the perform verb.

- **The extension root installs Kermit's writers in its `init`, which runs once** → The rig points the
  writers at the extension's destination **per call**, not merely restoring afterwards. Measured
  2026-08-26: with a restore alone, the first invoked cycle wrote `ext-debug.log` and every later one
  wrote the app's `debug.log`, so `/device/logs?process=extension` showed one cycle and a reader would
  conclude the rest never ran. Now ten consecutive cycles land in the extension's log and none leak.

- **A bound method reference to an `object` forces it where it is written** → The hook passes **thunks**,
  never `UploadExtensionRoot::processRawValue`. Measured: with a method reference, the extension root's
  `init` ran at hook-construction time and replaced the app's log destination before `SnapSyncRoot`
  initialised — `debug.log` was never created at all and the app's own `onLaunch` lines landed in
  `ext-debug.log`. This is the same rule the hook already followed for `SnapSyncRoot.app`/`.host`, and the
  reason it exists.

- **The substituted queue's state is reached from the cycle's dispatcher, not the caller's lane** → Every
  accessor takes a `Mutex`. An earlier version asserted the guard was unnecessary "because a cycle is
  invoked on one lane and runs to completion"; that is an assumption about a dispatcher the rig does not
  own, and it was measured false — one run of three answered with an empty `created` list while its own
  adapter log showed three jobs created in that cycle. An intermittently empty answer is worse than a
  wrong one, because "the cycle created nothing" is a legitimate outcome a scenario would record as a pass.

## Migration Plan

No runtime migration: nothing shipped changes behaviour. The device path is bit-for-bit equivalent apart
from D13's removed log line. Rollback is reverting the branch; a build made without `-Psnapsync.rig=true`
contains no rig source at all, so the substitution cannot reach a user.

The `ios-simulator` skill's instruction to pin `url_session` for every upload scenario is replaced by the
OS-driven path for `photokit` scenarios; the pin remains correct for exercising the app-driven tier.

## Open Questions

- Whether the forced-failure lever is best expressed **per job** at perform time or as queue state. Per-job
  is precise for scripting a chain against a known key; queue state matches `FakeBackgroundTransfer`'s
  existing `var` shape. Per-job is assumed here and is cheap to revisit.
- Whether `:test:world` should adopt the extracted registration port immediately (driving the ritual on
  JVM) or in a follow-up. This change makes it possible; it does not require it.
- Whether `OsExtensionView` should gain a third cell for "this host cannot register", now that a `false`
  here means something the existing `null`/`notApplicableReason` pair was written for but does not cover.
