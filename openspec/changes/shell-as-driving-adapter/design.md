## Context

Change 4 of the testing-concept sequence. Its brief was titled "the shell is a port" and asked for coverage of the
shell's tap → command transcription, leaving open whether that coverage is a contract, a static guard or both. This
design was settled in an operator interview. Verified against the tree on 2026-09-22 (`origin/main` = `24ff9103`):

- **The tap table exists three times.** `MainViewController.statusActions`, `ForgeViewController.statusActions`, and
  the body of `StatusPane` each build `StatusActions(...)` from a `StatusContainerHost`, about 45 lines each. Every
  field of `StatusActions` and its sub-bundles has an inert default, so an omission compiles. The copies already
  differ: only `MainViewController` wires `onOpenLink`. (The handoff's `transientError` divergence is stale: that
  value now travels in `UiState`.)
- **`SnapSyncRoot` transcribes every OS callback twice.** It has about 20 public `@PlatformEntry` members, each
  `log.invocation("X") { shell.X(...) }` over a `private interface Shell` / `private class LiveShell`. `LiveShell`
  launches flows, constructs `OsReceipt`s, and routes `handleBackgroundUrlSession` by identifier. That routing is
  the one pinned complexity suppression in `SnapSyncRoot.kt`.
- **`LiveShell` is almost entirely platform-free.** Its only iOS touches are `protectedDataAvailable()` (UIKit,
  logged only), the app's URLSession controller, BGTask re-scheduling, and the session-identifier constant.
- **Swift forwards through two same-shaped BGTask blocks** (`iOSApp.swift`): `download.backstop →
  runDownloadBackstop` and `upload.heartbeat → runUploadHeartbeat`, both `(() -> Unit) -> Unit`. That pair is the
  only Swift → Kotlin cross the compiler would not reject. `SwiftShellGuardTest` asserts only that each Swift
  function reaches *some* Kotlin root.
- **The `@PlatformEntry` population is no longer derived.** `PlatformEntryLoggingTest` was retired in `74302d2b`
  ("guards diagnosability rather than behaviour"), and `diagnostic-logging` already says the obligation is
  maintained by review. The KDocs of `PlatformEntry`, `RigHooks` and `Boot.kt` still claim a gate.
- **The contract mechanism is already general enough.** `Clause<K, T>` / `Binding<K, T>` are generic in the
  subject `T`, and `ContractCoverageTest` finds bindings anywhere in source by `: Binding<…>`.

## Goals / Non-Goals

**Goals:**
- The tap → intent transcription exists once, in a module with tests, and a host cannot silently omit a callback.
- The OS-callback transcription is core code covered on JVM and the simulator, and is specified by a contract any
  future driver of the core must pass.
- The shells hold nothing a mistake could hide in: the compiler writes the entry forwarding, and Swift cannot cross
  a background task.
- Everything crossing into the core is named for the need, not for iOS (an Android shell is a named future).

**Non-Goals:**
- Reversing "The app shells are wiring-only and untested": the rule stands, and only its residual risk shrinks.
- Covering the Swift layer's arguments: a correct entry called with a wrong same-typed argument stays uncovered.
- The URLSession adapter and its contract (phase 8), and the rig's retired `@PlatformEntry` coverage guard
  (phase 9).
- Reviving a derived `@PlatformEntry` guard.

## Decisions

### D1. The shell is a driving adapter; the entry surface is an inbound port the core implements

The OS drives the app, so in port/adapter terms the entry surface is **inbound**. It is declared in `ports/`,
implemented by the core, and called by a driving adapter. That adapter is the shell.

Alternatives, all rejected:

- **The iOS shell as the contracted implementation**, bound on `IOS_SIM_KEXE` from a bindings-only test source set
  in `:app:ios`. This covers the iOS half as well, but it reverses a rule the project argued for, needs an `:app:ios`
  simulator test executable (unmeasured: Sentry cinterop, framework export), and leaves the transcription where
  mistakes go unseen.
- **The whole app as a black box** over the rig's `/os` + `/user` + `/device/state` surface. This is phase 9's
  bundle, so choosing it pulls that phase forward.
- **A static name-matching guard** over the forwardings. It catches crossed names only, and restates the code as a
  table.

What decided it: drawn this way the transcription is ordinary core code, and the shell rule becomes *more* true
instead of acquiring an exception.

### D2. Two platform-neutral inbound ports

```kotlin
interface PlatformEntries {            // the app process
    fun onForeground()
    fun onBackground()
    fun onOpenUrl(url: String)
    fun onPushToken(hex: String)
    fun onSilentPush(payload: Map<Any?, *>, completion: () -> Unit)
    fun onBackgroundTask(identifier: String, completion: () -> Unit)
    fun onBackgroundTransfers(channel: String, completion: () -> Unit)
}
interface ExtensionEntries {           // the upload extension process
    suspend fun process(): CycleResult
    fun onTerminate()
}
```

- **Two ports, not one**, because the processes' entry sets are disjoint: one port would make every binding answer
  `Unreachable` for half its clauses.
- **Names are for the need** (`module-architecture`, "Ports are the I/O boundary named for the need"):
  - `handleBackgroundUrlSession` → `onBackgroundTransfers(channel, …)`: "the OS hands back finished background
    transfers for a channel".
  - `processRawValue(): Int` → `process(): CycleResult`.
  - The two BGTask entries → `onBackgroundTask(identifier, …)`.
- **Excluded:** UI reads (`renderHost`, `shareableCount`, `photoPermission`), because they are pulled by the UI, not
  called by the OS. Log-only callbacks (`onScene*`, `onPushTokenFailure`) and `onUserActivity`, whose input is a
  platform type the tested `forwardEventLink` filters before it reaches `onOpenUrl`, stay hand-written in the root.

### D3. The implementation lives in `compose/`; what it cannot name arrives as in-process hooks

The implementations sit beside `snapSyncApp`/`uploadCore`. They carry, verbatim from `LiveShell`:

- the flow calls;
- the `OsReceipt` construction with the per-entry deadlines;
- the `log.invocation` enter/exit, including the protected-storage state for background entries;
- the backstop re-schedule in a `finally`;
- the routing, now pure comparisons against identifiers passed in as data. The BGTask ids and the upload
  transfer channel are adapter constants and stay out of `model/`/`ports/`/`feature/`.

`:domain:compose` cannot depend on `:ui:presentation`, yet `onOpenUrl` must reach `StatusContainerHost.onOpenUrl`.
Three entries also touch the lazily assembled host first (foreground, push token, silent push), and the push token
goes to the root's token-source adapter. These arrive as a small hooks value the root supplies when it builds its entries
(`platformEntries(core = { app }, hooks)`), together with a `markActive` hook for the scene rule's
"has been active" record, which the foreground entry has always written first. Each hook is a call back into this process, which is
"coordination within the core" rather than I/O, so function types are legitimate. They are not an `AppPorts`
field: the host is built *from* `AppCore`, so the entries can only be built after it.

**As built:** the entries take a *provider* of the core, not the core. A delegation expression is evaluated
before the object's body, so the root's `by platformEntries(…)` runs at object initialisation. Building the
`AppCore` there would move graph assembly to process start and break the property a cold background wake
relies on. Resolving the core per call keeps assembly where the root's lazies always put it.
`extensionEntries(ports = { … }, cycle = { … })` does the same for the extension. The protected-storage line of
`onBackgroundTransfers` is written one dispatch after the (synchronous) routing, because the port's read may
hop threads and the handler must be adopted before its session can report drained.

### D4. The shells delegate by `by`

```kotlin
object SnapSyncRoot : PlatformEntries by entries { … }
```

The compiler writes the forwarding. Delegated members are ordinary members of the object, so they export to ObjC
exactly as the hand-written ones do today, and Swift call sites are unchanged apart from D5.

- **The extension** keeps one hand-written line,
  `fun processRawValue(): Int = runBlocking { entries.process() }.processingResultRawValue()`. `runBlocking` is not
  common API, and the Swift-only result type is constructed in Swift (`ios-photokit-upload`'s forcing proof ①
  stands).
- **Removed:** `LiveShell`, `Shell` and the session-routing suppression. `KotlinShellGuardTest`'s pin table drops
  `SnapSyncRoot.kt` to zero.

### D5. Swift forwards the OS's own task identifier

Both `BGTaskScheduler.register` blocks call `SnapSyncRoot.shared.onBackgroundTask(task.identifier) { … }`. The
identifier comes from the task the OS delivered, not from the literal beside it, so no copy-paste can route one task
to the other's handler. The core's routing is covered by the contract, including an unknown identifier, which is
released and logged. The registration literals themselves stay where `RuntimeIdentityTest` pins them.

### D6. Outbound effects reuse existing seams; one new port; nothing from phase 8

- `AppUploadEngine` gains `onBackgroundTransfers(completion)`. `UrlSessionUploadController.onBackgroundSessionEvents`
  is renamed to implement it. `onBackgroundTask()` is already on the seam.
- `AppPorts.scheduleBackstop` is unchanged.
- **New port `ProtectedStorage`** ("is protected storage readable now?"). Its iOS adapter reads
  `UIApplication.isProtectedDataAvailable` and lives in `:adapter:ios:app-only`, because UIKit is barred from
  `ext-safe`. Its honest fake goes in `:adapter:generic:fake`. It feeds log lines only and decides nothing. The
  Android analogue is `UserManager.isUserUnlocked`.
- A pinned `AppPorts` lambda for protected storage was rejected: it reaches out of the process, and the seam law
  says such a field is a port. `UrlSessionUploadController` does not move.

### D7. One contract per inbound port; outcomes observed through a binding-supplied handle

- **Subject.** `ShellSubject(entries, observe)` / `ExtensionSubject(entries, observe)`. The `…Observations`
  interfaces are declared beside each contract in `:test:contracts` and expose only what the clauses read: the
  join gate phase, imported count, landed objects, released completions, the registered push token, and so on.
  `:test:contracts` does not depend on `:test:world` (it links into the rig build, and `:test:world` hosts no
  contracts).
- **Bindings** live in `:test:integration` and build the core's real implementation over `World.core` in the named
  state. Both are `Live`: `JVM` and `IOS_SIM_KEXE`. `:test:integration` is where the real composition is exercised
  (`module-architecture`, "One shared composition": the wiring graph is not unit-tested) and it already runs on
  both targets. There is no `Fake` binding, because nothing licenses a double. The contract's worth is as the spec
  a second driver (phase 9's bundle, Android) must pass.
- **Clauses assert outcomes, never a call transcript.** A transcript restates the wiring, breaks on core refactors,
  and a second implementation passes by mirroring calls. Where an entry takes a completion, the outcome includes
  "released exactly once, after the work". A member with no observable outcome (`onTerminate`) gets no clause, and
  the contract says why.
- **Starting clause set.**
  - `PlatformEntries`:
    - a valid invite URL opens the join gate, and garbage shows the transient error;
    - foreground with a new in-window photo lands its object;
    - a silent push with a foreign photo on the backend imports it, then releases;
    - an unusable push payload still releases;
    - the backstop task drains pending imports, then releases;
    - an unknown task identifier releases;
    - the upload transfer channel reaches the app uploader, and any other channel the download jobs;
    - a push token reaches registration.
  - `ExtensionEntries`: pending work lands and reports `Completed`; nothing pending reports `Skipped`.

### D8. The tap table is one factory in `:ui:screens`

`fun statusActions(host: StatusContainerHost, shareableCount: …, photoPermission: PermissionStatus): StatusActions`
sits beside `StatusActions`. `:ui:screens` already has `api(:ui:presentation)`. All three hosts call it.

- `onOpenLink` is bound uniformly to `host.onOpenAppStore()`, which reads the store URL from state and is inert
  where the state holds none.
- It is tested in `:ui:screens`' `commonTest` with `runComposeUiTest`. Each test clicks a control of the real
  `StatusScreen` built from the factory over a real container and asserts the container's resulting state or the
  command it fired. This does not contradict the brief's "integration must not click": that objection was that
  clicking tests a copy which ships nowhere, and here the clicked table is the shipped one.
- The defaults on `StatusActions` stay for `:ui:screens`' own previews and tests. The requirement is that no *host*
  builds the bundle by hand.

### D9. `@PlatformEntry` and stale documentation

- The annotation moves onto the inbound port members. It stays on adapter OS callbacks and the hand-written root
  callbacks.
- Its KDoc, and those of `RigHooks` and `Boot.kt`, are corrected to say that no guard derives or checks the
  population, matching `diagnostic-logging`.
- Retiring the annotation was rejected here: its cost falls on adapters this change does not touch, and phases 5–8
  revisit each of them.

### D10. Spec accounting

Deltas: `module-architecture`, `testing-architecture`, `port-contracts`, `ios-app-shell`, `ios-photokit-upload`,
`diagnostic-logging`, `sync-status-screen`.

No delta:
- `architecture-guards`: its shell-gate requirement states the mechanism, not the pin inventory, which lives in
  `KotlinShellGuardTest`.
- `desktop-app-shell`, `desktop-test-harness`, `full-stack-harness`: they name `StatusPane` as the pane library,
  not its table.
- `upload-lifecycle`: triggers still reach the mechanism unconditionally.
- `push-registration`: the token still reaches `deliver`.
- `harness-world-model`: open; see Open Questions.

## Risks / Trade-offs

- **[Risk] Delegated members might not export to ObjC under the names Swift calls.** → Settle by compile: the first
  task builds the iOS framework and the Swift targets on the Mac runner (`ssh-mac-build`) with one delegated member
  before migrating the rest.
- **[Risk] The hooks are function-typed and outside `CompositionSeamTest`'s bundle set, so a hook that later
  reaches out of the process would not be seen.** → Keep the hooks in one named value with each field's reason
  documented. The seam gate's "bundle set is pinned" clause decides whether that value must join its inventory; if
  so, pin it.
- **[Trade-off] A contract with one implementation licenses no double today.** → Accepted and stated in the
  contracts' KDoc: it is the inbound ports' specification for the next driver. Ordinary `:test:integration` tests
  were the lighter alternative the operator declined.
- **[Risk] `runComposeUiTest` in `commonTest` builds its own scene.** → Intended: the factory is the unit under
  test, not the host's composition.
- **[Trade-off] Swift argument-level forwarding and the hand-written log-only callbacks stay uncovered.** → Stated
  in `testing-architecture` as the remaining residual.

## Migration Plan

Behaviour-preserving refactor. No data, no persisted state, and no backend change. Rollback is a revert. It ships as
an ordinary `internal` merge. The internal TestFlight build it produces is the device smoke test: foreground, a
silent push, and a backstop wake should log identically, apart from the renamed entry names.

## Open Questions

- Does `harness-world-model` need a delta? Only if `World` itself gains an entry surface. The bindings can build
  `core.platformEntries(worldHooks)` without one. Decide at apply time, when the binding code shows which.
