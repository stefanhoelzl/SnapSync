## Why

`testing-architecture` states the shells' residual risk in so many words: a zero-conditional forwarding
that names the wrong collaborator "passes every gate, compiles, and is wrong" ("The app shells are
wiring-only and untested", scenario "A shell forwarding is wrong but decides nothing"). That risk is
realised twice over in today's tree. The tap → intent table is written **three times**
(`MainViewController`, `ForgeViewController`, `StatusPane`), every field of `StatusActions` defaults to an
inert lambda, and the copies already disagree: only `MainViewController` wires `onOpenLink`. And
`SnapSyncRoot` hand-transcribes every OS callback twice — a public entry that wraps a private `LiveShell`
member, which launches the flow, holds the OS receipt and routes the background session — with nothing
checking any arrow of it.

The cause is a mis-drawn boundary, not a missing test. The shell is modelled as the *implementation* of
its entry surface, so the transcription sits in the one region the project declares untested. In
port/adapter terms the OS **drives** the app: the entry surface is an **inbound port the core
implements**, and the shell is its driving adapter. Drawn that way the transcription is core code,
covered like any other, and the shell rule stays exactly as written.

## What Changes

- **The tap table is built once.** A single `StatusActions` factory in `:ui:screens` binds a
  `StatusContainerHost` to the screen's callback bundle; the three hand-written copies are replaced by
  calls to it. It is click-tested in `:ui:screens`' `commonTest`, where the table clicked is the table that
  ships. A host passes only what it genuinely supplies (the shareable-count query, the grant), so an
  omission shows up in the signature rather than in a default.
- **Two inbound ports** in `:domain:ports`, named for the need rather than the platform:
  `PlatformEntries` for the app process (foreground, background, open URL, push token, silent push, a
  background task by identifier, handed-back background transfers by channel) and `ExtensionEntries` for
  the upload extension (`process(): CycleResult`, terminate).
- **The core implements them** in `:domain:compose`, beside `snapSyncApp`/`uploadCore`. That covers the
  entry → flow transcription, `OsReceipt` construction and holding, entry-point logging, and routing a
  background task or transfer channel to its handler. The routing becomes a pure comparison against
  identifiers passed in as data.
- **The shells only delegate.** `SnapSyncRoot` and `UploadExtensionRoot` implement their port by Kotlin
  delegation, so the compiler writes the forwarding. The extension shell still maps `CycleResult` to
  iOS's raw value with the existing tested function on the way out. `LiveShell` and its pinned
  session-routing suppression are removed.
- **Swift cannot cross a background task.** Both `BGTask` registrations forward the OS's own
  `task.identifier` to one entry, and the core routes it. Every other Swift → Kotlin call already has a
  distinct signature, so a cross is a compile error.
- **Outbound effects reuse existing seams.** `AppUploadEngine` gains `onBackgroundTransfers(completion)`,
  which `UrlSessionUploadController` already implements under another name, and `scheduleBackstop` is
  unchanged. One new need-named port, `ProtectedStorage`, carries "is protected storage readable now?",
  with an iOS adapter in `:adapter:ios:app-only` and an honest fake. It is diagnostic-only.
- **A port contract per inbound port** in `:test:contracts`. Each is bound `Live` on `JVM` and
  `IOS_SIM_KEXE` over `World.core`. Clauses fire an entry and assert core outcomes, including "the
  completion is released exactly once, after the work". There is no double, no recording and no device
  run.
- **`@PlatformEntry`** moves onto the inbound port members. Its KDoc, and those of `RigHooks` and
  `Boot.kt`, stop claiming that a guard derives the population. None has since `74302d2b`.

## Capabilities

### New Capabilities
<!-- none: the contract code is the inbound ports' specification (port-contracts), and the laws belong to module-architecture -->

### Modified Capabilities
- `module-architecture`: a new law, "OS entry points cross an inbound port", which the core implements
  and the shell only delegates to. "Shells are wiring only" changes from "forward entry points" to
  "delegate them".
- `testing-architecture`: "The app shells are wiring-only and untested" keeps its rule. Its stated
  residual risk shrinks to argument-level Swift forwarding plus the hand-written log-only callbacks.
- `port-contracts`: a clause's subject may carry observation handles the binding supplies, for a port
  that declares no reads of its own. An inbound port's contract observes the core's outcomes that way.
- `ios-app-shell`: the live composition root delegates its entries, and `MainViewController` takes the
  shared tap table. The receipt is constructed by the inbound port's implementation rather than the
  shell, protected-data state is read through `ProtectedStorage`, and upload-driving entries (including
  the background task routed by identifier) are forwarded by that implementation.
- `ios-photokit-upload`: "Cap-aware creation and tri-state processing result" — the extension exposes
  `process(): CycleResult` through `ExtensionEntries`, and the shell applies `processingResultRawValue`.
- `diagnostic-logging`: "Uniform platform-invocation logging" — a delegated entry point is logged by the
  inbound port's implementation, and `@PlatformEntry` marks the port members.
- `sync-status-screen`: a new requirement that the screen's callback bundle is built in one place, from
  the container, with no default that a host can leave in by accident.

## Impact

- **Code:**
  - `:domain:ports` (two inbound ports, `ProtectedStorage`)
  - `:domain:compose` (their implementations, `AppCore` wiring)
  - `:domain:feature` (`AppUploadEngine` widened by one member)
  - `:adapter:ios:app-only` (`ProtectedStorage` adapter)
  - `:adapter:generic:fake` (its fake)
  - `:ui:screens` (the factory and its tests)
  - `:app:ios`, `:app:ios:extension`, `:app:ios:forge`, `:app:desktop` (the shells and panes shrink)
  - `iosApp/` Swift (the BGTask forwarding)
  - `:test:contracts` (two contracts)
  - `:test:integration` (their bindings, over `:test:world`)
  - `:test:architecture` (`KotlinShellGuardTest`'s pin table shrinks by one)
  - `test/rig` KDocs
- **Unchanged:** user-visible behaviour, the backend, the URLSession adapter (phase 8 keeps it) and the
  rig's retired coverage guard (phase 9).
- **Label:** `internal`.
