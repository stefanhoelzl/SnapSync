## Context

Every behaviour worth testing on a device hangs off an OS callback the sandbox cannot fire. The app-driven
upload tier is kicked by a `BGProcessingTask` heartbeat or a background-`URLSession` relaunch; a headless
host fires neither, so a fully joined device with photo access granted and the tier armed produces no
upload cycle at all. Live state is observable only as pixels, and the extension's log costs a launch with
`SNAPSYNC_EXPORT_LOGS=1` plus a pull.

`:test:harness-driver` already solves the shape of this problem for the desktop harnesses: it serves the
real UI over HTTP with no window, and its stated defence is that *"clicks go through the real buttons of
the real panel, so there is no second way-to-drive that can rot or lie."* That defence — drive the real
thing, project nothing — is the constraint this design is built around.

Four gates bound where rig code may live, each verified against its source:

- `detektAppShell` sets its source to `files("app/ios/src", "app/ios/extension/src")` at
  `CyclomaticComplexMethod` threshold 2, gating; `KotlinShellGuardTest` mirrors that root list and pins the
  `@Suppress` inventory **by exact count per file, in both directions**. An HTTP router in either root fails
  the build.
- `MixedPortImplTest` scans `adapter/`, `domain/`, `ui/` and fails any file declaring an `interface` beside
  a Ktor import.
- `PlatformEntryLoggingTest` derives its population from `(?:SnapSyncRoot|UploadExtensionRoot)\.shared\.(\w+)\s*\(`
  over `iosApp/**.swift`. `SnapSyncRoot` is an `object`, which is what produces `.shared`; converting it to
  a class would silently empty that derivation.
- `ModuleSetTest` pins `settings.gradle.kts` against a 16-module target list and requires a new module's
  withholding argument to be recorded as a `module-architecture` delta.

Measured platform facts this design rests on (2026-08-09): `ktor-server-cio` 3.2.0 publishes `iosArm64`
and `iosSimulatorArm64` variants, and an `embeddedServer(CIO)` inside an `iosSimulatorArm64` test binary
answered a Darwin-client GET with its body. `pymobiledevice3 usbmux forward` forwards a host TCP port to
the device over usbmuxd with no root and no developer tunnel.

## Goals / Non-Goals

**Goals:**

- Force any OS-callback entry point on a physical device from the sandbox, over `curl`.
- Read the real `UiState` and the ledger the status source reads, without a screenshot.
- Read both processes' device logs without a relaunch.
- Contain every byte of this at compile time, so no shipped build can serve anything.
- Leave the app's behaviour under a rig build indistinguishable from production until a request arrives.
- Not foreclose the simulator host, the extension-shaped second process, Gherkin scenarios, two-member
  runs, or backend fault levers.

**Non-Goals:**

- Scenarios, a scenario vocabulary, or Cucumber. The bounded-wait helper those need belongs with them.
- Simulator support. The fixed default port is device-only by construction.
- Fault injection, and fakes inside the app. Faults belong to the local backend rig.
- In-process state reset. Reset is a process relaunch: the shipped app has no teardown path, `onLaunch`
  registers `NSNotificationCenter` observers documented as never removed, and cold start is where several
  historical bugs lived.
- A verb to stop the OS upload producer (see D14).

## Decisions

### D1 — The rig is its own module, `:test:rig`, at `test/rig/`

`test/` is where this repo already puts non-gating dev infrastructure with no spec (`:test:harness-driver`,
and by posture `ssh-mac.yml` and `api/src/dev`). The placement also misses all four gates above by
construction: outside `adapter|domain|ui`, outside both shell roots, and not one of the three files
`PlatformEntryLoggingTest` names.

Its withholding argument — required by `module-architecture`'s "the module set withholds; packages
organize" — is that it is the **only** module permitted to depend on `ktor-server-*`. A server import
anywhere else is a compile error.

One property is unlike every other `test/` module and is accepted deliberately: this one links into a real
device binary. It never links into a TestFlight or App Store build.

*Rejected:* `:app:ios:rig` — names a platform the code deliberately does not depend on, and sits one glob
widening of `appShellSources` away from being scanned by the shell gates. A top-level `rig/` — honest, but
a fifth root no existing convention explains.

### D2 — `commonMain`, iOS targets only, no jvm target, no tests

The rig is written against `:domain` types (`AppCore`, the entry points, the read-model `StateFlow`s), all
platform-free by law, so `commonMain` costs nothing and makes an Android target a build-file edit rather
than a rewrite. Platform-bound verbs arrive as injected lambdas the host shell supplies — the same shape
`flow/` already uses for port touches.

No tests, matching `:test:harness-driver` exactly. **This is conditional, and the condition is the spine of
this design: the rig may contain no projection it could get wrong.** Every decision below is checked
against it. If a future change needs hand-written inference in the rig, it needs a `jvm()` target and tests
with it — `:test:world`'s `World.core` is the same `AppCore`, so that path stays open.

### D3 — Zero lines in `SnapSyncRoot`; the module contributes its own hook

Kotlin has no conditional compilation, so something in the always-compiled graph must name `RigServer` in
one build and not the other. Under `-Psnapsync.rig=true`, `app/ios/build.gradle.kts` adds
`test/rig/src/hook/` as a source directory — compiled **into** `:app:ios`, so it reaches `SnapSyncRoot` at
`internal` visibility — and adds the project dependency. Without the property it adds neither, so a
production build contains no rig source at all: not a stub, not an inert branch. The hook self-starts via an
eager top-level initializer.

The only production diff in the shell is two fields widened `private` → `internal`: `app`, so the hook can
pass the core as a thunk, and `mode`, so `/health` can report the composition the app actually resolved
rather than resolving it a second time (a second resolution is a second answer that can disagree).
`internal` is module-wide and is **not** exported to the ObjC framework — verified on device: `SnapSyncRoot`'s
generated ObjC interface carries only `alloc`/`allocWithZone`/`init`/`shared`, and the header's two `AppCore`
mentions are both doc comments.

*Rejected:* a `startRig { app }` line in `SnapSyncRoot` with a swapped-in `= Unit` stub — works, depends on
no experimental behaviour, but puts test-infra vocabulary and a permanent dead file in the production
composition root. Retained as the fallback (see Risks). *Rejected:* always linking `:test:rig` and letting
it hollow itself — puts a `test/` module on the App Store binary's link line and moves containment out of
sight. *Rejected:* making `app` `public` so the rig module could pull — exports `AppCore` and everything
reachable from it into the framework header in every build.

### D4 — The core is passed as a thunk; the socket binds without forcing anything

`SnapSyncRoot.app` and `.host` are `by lazy` deliberately: *"nothing resolves the device identity or opens a
protected store earlier than before (the locked-background-launch property)"*, and touching `host` calls
`installPermissionSubscriptions()`, which `ios-app-shell` has a scenario forbidding on a cold background
wake. The rig therefore receives `() -> AppCore`, binds its socket at launch, and forces the graph only when
the first request arrives — at which point it forces exactly what a real entry point would.

### D5 — A trigger is a platform entry point, not a `flow/` class

**Forcing proof:** `SnapSyncRoot.onForeground()` is `scope.launch { host; app.foregroundFlow.run() }`. The
`host` touch assembles the live stack and installs the grant subscriptions; that is the shell's work, not
the flow's. A rig calling `foregroundFlow.run()` would be a second way-to-drive that silently omits it —
precisely what `Driver.kt`'s rationale forbids. Three entry points have no flow at all (`onPushToken`, which
the push design depends on; `handleBackgroundUrlSession`; `runUploadHeartbeat`), and four carry an OS
completion handler that D6 turns into the completion signal.

Driving the real member also means the trigger logs through `Logger.invocation` exactly as an OS-driven one
does, so a rig-driven foreground is indistinguishable in `debug.log` from a real one.

Portability is preserved at the map, not the call: trigger names come from an injected map the platform hook
supplies, so the rig core stays common and another platform brings its own map.

### D6 — A trigger returns what the platform returns; nothing classifies

Four entry points are handed an OS completion handler, and all four already wrap it in `OsReceipt`:
`SnapSyncRoot.kt:1318` (`onSilentPush`, 20 s), `SnapSyncRoot.kt:1344` (`runDownloadBackstop`, 120 s),
`UrlSessionUploadController.kt:~166` (`URL_SESSION_EVENTS`, 20 s) and `~276` (upload heartbeat, 120 s). That
file states the population is closed: *"every OS handler in the app is released by the same bounded,
release-exactly-once path rather than by four hand-written `finally`s."*

`OsReceipt`'s `release: () -> Unit` **is** the OS handler. The rig, playing the OS, supplies it — so it does
not detect completion, it **receives** it, on the same channel the OS does. Those triggers block until
release and report `heldMs` and `deadlineMs` as measured facts. The four entry points the OS does not wait
on return `202` immediately, because waiting would make the rig-driven path differ from production in timing
and interleaving — the exact fidelity D5 exists to preserve. The boundary is derived from the platform
contract, not chosen.

**Why not await scope quiescence.** It cannot terminate: `installPermissionSubscriptions()` launches three
permanent collectors as direct children of the composition scope (`SnapSyncApp.kt:938`, `:944`, `:956`),
`host` launches a fourth for push registration, and `StateFlow.collect` never returns — so
`scope.job.children.joinAll()` hangs from the first realization of `host`, and the first `/trigger` would
hang rather than answer. A before/after child-set diff does not rescue it either: `SnapSyncApp.kt:509`
launches on the **scope**, not on the trigger's job, and its own comment — *"on its own escaping launch
(like Provision's reconcile)"* — establishes that as a documented idiom with more than one site, unenumerated
and ungated. Any scheme that must know about them rots the next time one is added, and it rots toward
passing early. And even a perfect quiescence would answer the wrong question: `IosUrlSessionUploadPlatform`
enqueues `NSURLSessionUploadTask`s and returns, so the coroutine settling never meant the bytes landed.
Every real assertion is eventual and polls `/state` regardless.

**Why the rig reports numbers and not a verdict.** `release` carries no outcome — both branches of
`heldFor` and its `finally` call `releaseOnce()` with no argument — so `settled` versus `deadline-expired`
is not recoverable from the lambda. Inferring it from `heldMs ≈ deadlineMs` is ambiguous in a band (19.99 s
against a 20 s deadline) and would be exactly the projection D2 forbids. The authoritative answer already
exists: `heldFor` emits its `log.w` line **only** on the expiry path, and its KDoc says why — *"a bound that
fires invisibly is indistinguishable from work that finished, and this line is the only evidence that the
mechanism protecting the app actually engaged."* That line is readable through `/logs` after the `[rig]`
marker, with no race: `FileLogWriter.log()` calls `appendAtomically`, a direct
`open(path, O_WRONLY or O_APPEND)` plus write — no buffer, no queue — so the line is on disk before
`releaseOnce()` runs, therefore before the HTTP response.

This is not only a return semantic. The receipt says what the app **claimed**; world state says what is
**true**; the gap between them is the SNAPSYNC-6 bug class, whose fix (`22f782bd`) shipped with no
regression test possible on any host. A scenario can now assert both.

### D7 — Trigger coverage is gated, not hand-picked

A new `:test:architecture` guard derives the `@PlatformEntry` population the way `PlatformEntryLoggingTest`
does and asserts every member is either wired or in a named exclusion list carrying its reason. A
hand-picked list rots silently, and the only symptom is a scenario nobody wrote. Adding an entry point
already costs two edits (annotate, log); this makes it three.

Wired beyond the obvious set: `onSceneContinueActivity` (the warm universal link — the SNAPSYNC-6 path,
otherwise reachable only by physically scanning a QR; the rig fabricates the `NSUserActivity` iOS would
deliver) and `handleBackgroundUrlSession` (which exercises the session-identifier routing — one of only two
pinned `@Suppress("CyclomaticComplexMethod")` sites in `SnapSyncRoot`, currently untestable by any means).

Excluded with reasons: `onLaunch` re-registers `NSNotificationCenter` observers documented as never removed,
so invoking it twice corrupts the process under test; `applyLaunchEnvMembership` and
`applyLaunchEnvPhotoLibrary` read the process environment, which is fixed for the life of the process.

### D8 — `/state` serializes the real `UiState`, mechanically

`@Serializable` goes on the sealed hierarchies where they are declared, in `:ui:presentation`, and the
compiler plugin generates the encoder. A rig-side DTO mapped from `UiState` was rejected under D2: a
hand-written mapping is a second rendering that can drift, with nothing to catch it. The presentation gate
forbids only `ports/` and `flow/` **project** refs, so a `kotlinx.serialization` import is clean; `EventStart`,
`EventEnd` and `DeletesAt` already carry serializers.

`UiState` carries no counts by design — *"the screen answers 'is it healthy?', not 'how many of N'"* — so
`/state` also aggregates, by direct `.value` reads with no transformation: membership readiness
(`configResolved` plus the active `eventId`), ledger aggregates from `AppCore.ledgerCounts`, the
screen-level read-models the container exposes beside `UiState`, and the resolved composition facts.

Readiness is not a convenience. `onForeground` fires **before** the membership config resolves — measured at
17:53:25.23 against 17:53:27.45 — so a caller that triggers then asserts reads a membership-less state and
concludes nothing happened. Exposing the fact replaces a sleep with a poll on a stated condition.

### D9 — `/logs` passes `DeviceLogSource.tail` through

The port already exists on `AppPorts` with `enum Process { APP, EXTENSION }`, so this is a pass-through with
no new logic, and the extension's log becomes one request instead of `SNAPSYNC_EXPORT_LOGS=1` plus a
relaunch plus a pull. It inherits the port's honest bounds (tail only, current file only, never the `.1`
roll) and must surface the port's `null` as a **stated reason**, never an empty `200` — re-collapsing an
absence the port was careful to keep distinct is the failure mode this repo keeps paying for.

### D10 — The channel logs its own requests

Every request writes a `[rig]` line through Kermit, as every `@PlatformEntry` member does. Without it the log
cannot say whether a trigger came from the rig or the OS — the ambiguity `PlatformEntryLoggingTest` exists to
eliminate on the OS-driven side. It also serves as the log cursor: "what happened since the trigger" is
everything after the marker, so no rig-side offset state is needed.

### D11 — Fixed device port, hook-read override, loopback only

A device runs one instance of the app, so the collision hazard `:test:harness-driver`'s port file guards
against does not exist here; the host-side local port is chosen freely by `usbmux forward`. Default `18099`,
overridable by `SNAPSYNC_RIG_PORT`, **read by the hook file** — which does not exist in a non-rig build, so
unlike every `SNAPSYNC_*` trigger this one is inert by construction rather than by a runtime check, and
`LaunchDirectives` is untouched.

The default is **device-only**: all simulators share the host's loopback, so the simulator host must set the
override per instance. Bind address is `127.0.0.1` and nothing else — a phone on a shared network must not
expose a channel that forces OS callbacks and reads event state.

On bind failure the rig logs at `Error` severity and the app runs on unchanged. The rig must never break the
app under test; but `connection refused` is ambiguous between "app not running", "forward not set up" and
"rig failed to bind", and the log line — pullable without the rig — is what separates them.

### D12 — No spec for the rig

This repo specs test infrastructure that **holds behaviour** (`:test:world`, both desktop harnesses, whose
spec says the inspector *"holds no logic — the real logic lives in `:test:world`"*) and does not spec test
infrastructure that is a **lens** (`:test:harness-driver`, `ssh-mac.yml`, the local backend rig). After D6
removed the last piece of rig-owned inference, every surface is a mechanical projection of a contract
specified elsewhere. A rig spec would restate them.

### D13 — No `ios-app-shell` delta

`SNAPSYNC_FORGE_STATE` is spec'd there because **production code reads it**: `LaunchDirectives.kt:94` parses
it in every shipped binary and `CompositionMode.kt:96` branches on it. Nothing shipped can observe
`SNAPSYNC_RIG_PORT`, and no shipped behaviour depends on the rig, because the code is absent. Its home is the
rig's runbook, exactly where `:test:harness-driver` documents its port file.

### D14 — Two things deliberately out of scope

The **OS-producer stop verb** is spun out. `UploadArm.switchTo` already deregisters the extension
(`if (producers.osDriven != null && producers.osDriven !== target) producers.osDriven.stop()`), and that
`stop()` is what calls `setUploadJobExtensionEnabled(false)`. It is merely disconnected under the tier-force
flag, where `LiveShell` passes `osUploadProducer = { null }`. Un-nulling alone is insufficient:
`selectedProducer()` reads `producers.osDriven ?: producers.appDriven` under `GRANTED`, so the flag would
stop forcing anything. The fix — `ComposedProducers` distinguishing selectable from stoppable — is a real
`upload-lifecycle` change that benefits every operator, not just rig builds, and it follows this change so it
can be verified through the channel.

The **main-thread stall monitor** is dropped. `Logger.invocation` already records enter/exit and duration for
every entry point, and `/logs` now reaches it.

### D15 — Device-identity injection: shape decided, implementation deferred

`KeychainDeviceIdentity` cannot work on a simulator — adding `keychain-access-groups` makes the app
un-launchable in every signing form measured, and omitting it yields `errSecMissingEntitlement` (-34018). The
device id must therefore be injectable, and a channel verb cannot do it: the id is resolved during
composition, long before any request arrives. It will be `SNAPSYNC_DEVICE_ID`, in the existing launch-env
idiom, with the property that makes it safe on a written-once, unrecoverable value: **it fills an absence and
never overwrites**. A successful Keychain read ignores the variable and says so in the log.

## Risks / Trade-offs

- ~~**`@EagerInitialization` may not fire in a static Kotlin/Native framework.**~~ **SETTLED — it fires.**
  Measured 2026-08-09 on the SE2 (iPhone12,8, iOS 26.6; Xcode 26.6, Kotlin 2.4.0), Debug archive, manually
  re-signed: the eager initializer ran, bound the socket, and `/health` answered
  `rig=up port=18099 bootedAt=2026-08-09 19:45:42 +0000`. Ordering against `SnapSyncRoot`'s own init landed
  in the **same millisecond**, with the rig's line immediately after `=== app process start ===`, so the
  Kermit file writer was already installed and the rig's lines land in `debug.log` after all. The exact
  relative order of the two initializers is *not* resolvable from this data — the rig's log line is emitted
  from its own coroutine and `bootedAt` has second resolution — so `/health` remains the reliable oracle and
  the hook still avoids touching `SnapSyncRoot` at boot. `onLaunch` completed in 1 ms; launch is unaffected.
- ~~**`ktor-server-cio` is unproven on a physical device.**~~ **SETTLED — it serves.** Same run: a Darwin
  `curl` reached `GET /health` over `pymobiledevice3 usbmux forward 18099 18099` and got the body back. This
  is device-side execution, not the artifact list and not the simulator.
- **Containment measured, both directions** (same run): with `-Psnapsync.rig=true` the app binary contains
  `_kclass:app.snapsync.rig.RigServer`; with `-Psnapsync.rig=false` the framework contains **zero**
  `app.snapsync.rig` symbols. And `internal` does not leak: `SnapSyncRoot`'s exported ObjC interface carries
  only `alloc`/`allocWithZone`/`init`/`shared`, and the header's two `AppCore` mentions are both doc comments.
- **A transport timeout below 120 s makes `deadline-expired` indistinguishable from a dead rig.**
  `BACKGROUND_TASK` is 120 s, so `/trigger/downloadBackstop` can legitimately block that long. → The rig
  imposes no request timeout below the largest receipt deadline, and the runbook's `curl` carries a uniform
  `--max-time` comfortably above 120 s, which covers both trigger groups without per-endpoint knowledge.
- **The `OsReceipt` expiry line's text becomes a load-bearing cross-process contract with nothing pinning
  it.** Absence of the line reads as "settled", so a reword turns every receipted scenario green while hiding
  the regressions they exist to catch — silent, in the dangerous direction. → A guard in
  `architecture-guards` pins it, beside `RuntimeIdentityTest`'s literal pins. Extracting the substring into a
  public `ports/` constant was rejected: it adds `:domain` surface whose only consumer is test equipment,
  which is the concession refused throughout this design.
- **The rig's hook compiles into `:app:ios` but sits outside the shell gates' path scan.** → Rather than
  record an exemption someone must remember, `test/rig/src/hook/` is added to `appShellSources` and to
  `KotlinShellGuardTest`'s mirrored root list (which must move together — it has a non-vacuity floor). The
  hook is a map literal plus a constructor call, so it passes today; if it ever needs a branch, failing loudly
  is correct.
- **`0.0.0.0` is a one-character regression that reads as a bugfix.** → A guard asserts the rig names no bind
  address but the loopback constant.
- **Forcing the URLSession tier on an iOS ≥26.1 device before the spun-out change lands leaves two
  `LedgerWriter`s over one App-Group ledger.** → Do not tier-force in a rig session until then; recorded in
  the runbook.
- **The first request forces the lazy graph.** Accepted and deliberate: it forces exactly what a real entry
  point forces, and D4 keeps launch untouched. A rig build's launch behaviour is identical to production's.
- **`:ui:presentation` gains a compiler plugin and a serialization dependency for a surface only the rig
  reads.** Accepted: the alternative (a rig-side DTO) trades a permanent, mechanical annotation for
  hand-written inference in the module that has no tests.

## Open Questions

- Where the bounded-wait helper lives once scenarios exist — most likely with them, since it is JVM and
  testable, and the rig has no tests by design. This change only has to not preclude it, and it does not.
- Whether the scenario layer should be able to *enforce* that scenarios never hand-roll a wait. Relevant to
  the previous question, not to this change.
