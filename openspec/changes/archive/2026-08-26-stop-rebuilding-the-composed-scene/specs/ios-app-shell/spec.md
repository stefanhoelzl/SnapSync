## MODIFIED Requirements

### Requirement: iOS application shell
The system SHALL provide an iOS application built with Compose Multiplatform whose entry point is a
`ComposeUIViewController` (in the `:app:ios` module) that hosts the shared `StatusScreen`. The screen
SHALL render **live** `UiState` observed from an assembled real stack —
`StatusContainerHost.container.stateFlow` — not a static `UiState`. The Swift entry point (`iosApp/`)
SHALL remain a trivial pass-through that obtains the root view controller from `MainViewController()`.

That view controller SHALL be obtained **only while the app is active**. A process launched or woken into
the background — by a silent push, a `BGTask`, or a background `URLSession` event — SHALL compose **no**
scene: no `ComposeUIViewController`, no Compose runtime, and no renderer. The scene SHALL be composed at
the first activation and not before.

Whether a scene is composed and whether an already-composed scene is **rebuilt** are two different
questions, and the second SHALL be answered from **what the shell has already handed out**, never from
which observer of the activation notification happened to run first. The shell advances a rebuild signal
as it hands each scene out, by a pure, tested rule: handing out a placeholder advances it; handing out a
live scene carries it forward unchanged. The app-level foreground entry and the first evaluation of the
platform view therefore race harmlessly — BOTH orderings SHALL produce a correct result, which is what
makes their order not worth contracting.

That signal SHALL be **monotonic** across the life of a process. The UI framework rebuilds on a CHANGE in
the value, not on its magnitude, so a signal that fell back would rebuild exactly as surely as one that
rose. A rule that answered from the mode most recently handed out satisfies every sentence above and is
still wrong for this reason: once the placeholder is replaced, the most recent mode is the live one, the
answer falls, and the next ordinary foreground rebuilds a scene that did not need rebuilding. Measured on
a simulator (iOS 26, 2026-08-26) as a third scene construction in one process, on the first warm
foreground.

A rebuild SHALL create a new view controller. The shell SHALL NOT hand the platform a controller it has
already installed: the framework's contract is that its factory *creates* the controller and that
teardown *removes* it, so returning an already-installed instance makes one object simultaneously the
thing the incoming host adopts and the thing the outgoing host removes — measured on device to leave the
scene detached and the screen blank until the process restarts.

This is a **mitigation for a renderer defect, not an architectural preference**, and SHALL be described as
such wherever it is documented. Apple's contract is that a backgrounded app must not submit GPU work
(`kIOGPUCommandBufferCallbackErrorBackgroundExecutionNotPermitted`) and that a backgrounded app's GPU
resources are reclaimed; a Metal-backed renderer is expected to free them on background and rebuild them on
foreground. Compose Multiplatform 1.11.1 does not, and the observable consequence is a scene composed while
invisible, kept for hours, and then presented drawing its texture-backed content — glyph atlas, cached
`ImageBitmap`s, cached vector layers — blank or corrupted, while plain geometry still draws. Two production
reports on different devices, OS majors and upload tiers exhibited exactly that, with no hang, no crash and
no memory signature. **Expiry trigger:** the upstream defect
([CMP-5978](https://youtrack.jetbrains.com/issue/CMP-5978)) fixed in a Compose Multiplatform release this
project adopts — at which point this condition SHALL be re-evaluated and removed if the renderer honours the
contract.

Deferring the scene SHALL NOT defer any other work. Every background trigger runs off the composition root's
`AppCore`, which is independent of the UI container; nothing outside `MainViewController` observes
`renderHost`. A background wake SHALL therefore behave exactly as before, minus the scene.

The decision of whether to compose SHALL be a **pure, tested resolver** consumed by a single `when` in
`:app:ios`, following the sealed-mode pattern the composition-mode resolver already establishes. Swift SHALL
express it as wiring — an assignment or a bound value — and SHALL contain no conditional, so the
transcriber law continues to hold at zero decisions.

The app SHALL declare the **associated domain** `applinks:<link domain>` in `iosApp.entitlements`
(capability `event-link`), registering it as the handler for the event link's Universal Link, and SHALL
register **no** custom URL scheme (`CFBundleURLTypes` SHALL be absent — the `snapsync` scheme is
retired).

An opened event link SHALL reach `SnapSyncRoot.onOpenUrl(_:)` as a **raw string**, performing no parsing
in Swift, in **both** of these cases:

- a **cold launch** — the link was opened while the app was **not running**; and
- a **warm delivery** — the link was opened while the app was running or suspended in memory.

Cold launch is the case that matters most and SHALL NOT be treated as the incidental one: a recipient
tapping an invite for the first time never has the app running, and bootstrapping that recipient is why
the event link exists (capability `event-link`).

Link delivery SHALL be independent of scene composition: a link SHALL reach `onOpenUrl` whether or not a
scene has been composed. The delivery hooks SHALL include **both** the scene delegate's callbacks and
SwiftUI's `.onOpenURL` on the `WindowGroup`. Both are declared because neither covers every case —
measured, on the builds named below — and NOT because the mechanism that makes each fire is understood.
It is not: the same modifier was measured failing in an earlier delegate configuration and firing in
this one, which no available explanation accounts for. The contract is therefore the outcome, and the
redundancy is what makes the outcome robust to an explanation nobody has.

The string forwarded SHALL be the **complete** URL including its fragment, which carries the entire
payload (capability `event-link`) — a truncated URL is an empty invite.

This requirement fixes the **outcome**, not the mechanism. Which platform callback delivers the link is
an implementation decision recorded with its evidence (decision record below), because it is an
incidental platform detail rather than part of this capability's contract. A previous revision of this
requirement mandated a specific mechanism (SwiftUI's `onOpenURL`) *and* asserted it handled both cases;
the mechanism cannot do so, so a conforming implementation was broken — and nothing could contradict the
spec, because this module is untestable by rule. Pin a mechanism here only where the mechanism **is** the
contract.

Each delivery hook SHALL forward under a **distinct entry-point name**, so the device log names which
hook the platform actually invoked (capability `diagnostic-logging`). That is what MEASURED the iOS 18
gap below: two hooks indistinguishable in the log would have read as "a link arrived once" and settled
nothing.

Delivery SHALL work **whether or not the app is already running**, and the shell SHALL NOT rely on any
single platform hook to achieve that. Neither available hook is sufficient alone, and both statements
are measured, on the configuration and builds named: with a custom scene delegate installed, the scene
delegate's continuation does not fire on iOS 18.7.9 while the app is running (builds 681/683, iPhone
XS; `scene(_:willContinueUserActivityWithType:)` announces and nothing follows, from Notes, WhatsApp
and Safari's smart banner alike), and SwiftUI's `.onOpenURL` fired for only 2 of 4 deliveries on iOS
26.6 (build 687, SE2). The union of the two delivered in every measured configuration. A previous
revision of this paragraph asserted that iOS 18.7.9 does not call `scene(_:continue:)` at all — a claim
about the **platform**, disproved on that same OS build once `.onOpenURL` was restored. Scope such
claims to the build and configuration measured; expiry: re-measure at the next iOS major, and whenever
a delivery hook is added or removed.

The scene delegate SHALL **record every callback it receives**, not only those carrying a link — the
connection (including one carrying no activity at all), a continuation UIKit announces before
attempting it, and the scene lifecycle. Without this, a delivery that fails is indistinguishable from a
link the platform never routed to the app, and those two have different causes and different fixes.
That is not a hypothetical: separating them is exactly what the dumps above could not do, and it cost
the investigation a device session. These recorders SHALL decide nothing and route nothing — a
diagnostic that changes behavior is no longer a diagnostic.

Delivery SHALL be **exactly once** per opened link — and that SHALL be enforced by the app, in tested
code, rather than assumed of the platform (capability `event-link`). A link that provisions twice is a
bug, and the platform demonstrably delivers the same link more than once: measured on build 687, the
same URL arrived twice on an iOS 18.7.9 cold launch (~130 ms apart) and twice on iOS 26.6 both while
running (8 ms) and cold (105 ms). Because the app deduplicates, **more than one delivery hook MAY be
live**, and that redundancy is the availability strategy rather than a hazard: no hook is reliable on
every OS, and a hook that fires twice costs nothing once the second delivery is a no-op. This inverts a
previous revision, which forbade "stacking redundant delivery hooks" — correct while delivery was not
idempotent, and obsolete now that it is. What a future reader SHALL still not do is add a hook and
*assume* it composes: each one forwards under its own entry-point name so a dump can count deliveries,
and the count is what proves this requirement.

The mechanism by which the shell re-asks for the root view controller at activation is an
implementation decision, not part of this contract — but it SHALL key on a signal that fires however the
app is opened, **including a headless developer launch** that foregrounds the process without connecting
a scene session. A scene-level callback does not satisfy that (measured 2026-08-06: a `dvt launch` app
never received `sceneDidBecomeActive` and showed a black screen), and losing the headless path would cost
this project the only way an agent can see the app at all.

The deferred placeholder SHALL paint the platform's own system background colour rather than being left
untinted. A launch screen with no configured content, an untinted placeholder and a detached scene are
otherwise the same white, which is indistinguishable to a reporter and to whoever reads their dump; it
also flashes white on every cold launch in dark mode. This SHALL NOT be read as a promise that the three
are visually distinguishable in every appearance — in light mode the system colour IS white, and the log
line below is what separates them.

The **resolved rebuild signal** SHALL be recorded in the device log alongside the resolved mode, under the
same platform-invocation logging (capability `diagnostic-logging`). Recording only that the activation
entry point ran is not sufficient: what distinguishes a healthy process from one carrying a stale rebuild
signal is the VALUE it answered, and without it the distinction can only be inferred from the absence of a
deferred-mode line elsewhere in the log.

The **resolved mode** SHALL be recorded in the device log under the shell's platform-invocation logging
(capability `diagnostic-logging`) — not merely the fact that the entry point ran. The entry point runs in
both cases; what distinguishes them is what it returned, so the log SHALL name it. That line is the
verification of this requirement, and no additional instrumentation is required for it: a report can then be
read for whether a scene was composed before the process was ever active.

The extension target SHALL NOT declare an associated domain: it never handles URLs.

#### Scenario: Launching the app into the foreground shows live status
- **WHEN** the iOS app is launched and becomes active
- **THEN** a `ComposeUIViewController` presents the shared `StatusScreen` rendering the current
  `UiState` from the live container, updating as config, permission, and ledger state change

#### Scenario: A background-launched process composes no scene
- **WHEN** the process is launched or woken by a silent push, a `BGTask`, or a background `URLSession`
  event, and never becomes active
- **THEN** no `ComposeUIViewController` is created, and every scene-mode the device log records for that
  process is the deferred one

#### Scenario: The first activation composes the scene
- **WHEN** a process that was launched into the background is later brought to the foreground
- **THEN** the scene is composed at that activation, and the log records the live mode only at or after
  activation, never before it (the app-level foreground entry and the scene composition ride the same
  notification; their order is not contracted BECAUSE the rebuild rule above makes both orderings
  correct, not because the consequences were left open)

#### Scenario: A later activation does not rebuild the scene
- **WHEN** an app whose scene is already composed is backgrounded and brought forward again, repeatedly
- **THEN** the rebuild signal reads the same value at every one of those activations, so the platform is
  not asked for the root view controller again, the same scene is presented, and screen-local state such
  as an open settings surface or a half-typed report survives

#### Scenario: A process that composed its scene before the first activation is not rebuilt
- **WHEN** the activation notification is observed before the platform view is first evaluated, so the
  very first resolution is the live mode and no placeholder is ever installed
- **THEN** that activation and every later one request no rebuild, and the app remains rendered — the
  shape that previously left a live scene installed against a stale rebuild signal and blanked the
  screen on the next ordinary foreground (Bugsink SNAPSYNC-15, SNAPSYNC-24)

#### Scenario: A rebuild never reuses an installed controller
- **WHEN** the shell is asked for the root view controller a second time in one process
- **THEN** it returns a newly created controller rather than one it has already handed out, so the
  outgoing host's teardown cannot detach the controller the incoming host just adopted

#### Scenario: The placeholder is not bare white
- **WHEN** a process composes the deferred placeholder
- **THEN** the placeholder paints the platform system background colour, so a dark-appearance device
  shows no white flash on the way to the live scene

#### Scenario: The log names the rebuild signal, not just the entry point
- **WHEN** the app becomes active
- **THEN** the device log records the value the activation entry point answered, so a report can be read
  for whether that process was carrying a stale rebuild signal without inferring it from what is missing

#### Scenario: A background wake still does its work without a scene
- **WHEN** a silent push wakes a process that composes no scene
- **THEN** the wake's reconcile, upload-cycle and download work run exactly as they would with a scene

#### Scenario: UI is the real shared screen, not a placeholder
- **WHEN** the status screen is displayed
- **THEN** it is the same `StatusScreen` composable the desktop app uses (from `:ui:screens`, themed
  via `AppTheme`), not an iOS-specific placeholder

#### Scenario: First frame is honest
- **WHEN** the app launches with photo access already granted and the ledger has not yet been read
- **THEN** the first frame is `UiState.Loading` ("Loading …"), never a guessed `NeverSynced` that
  later corrects (subject to the setup gate: if config is absent the first frame is `UiState.Setup`)

#### Scenario: A scanned QR opens the app on a COLD launch and forwards the raw URL
- **WHEN** the stock Camera app opens a `https://<link domain>/join#…` event link while the app is **not
  running**
- **THEN** iOS matches the app's associated domain, launches the app, and the raw URL string — fragment
  included — reaches `SnapSyncRoot.onOpenUrl(_:)` without parsing, so the join gate opens on that event

#### Scenario: An event link opened while the app is running is forwarded too
- **WHEN** an event link is opened while the app is running or suspended in memory
- **THEN** the raw URL string — fragment included — reaches `SnapSyncRoot.onOpenUrl(_:)`

#### Scenario: A link arriving at a process with no scene is still forwarded
- **WHEN** an event link is opened against a process that was woken into the background and has composed
  no scene
- **THEN** the raw URL string still reaches `SnapSyncRoot.onOpenUrl(_:)` exactly once

#### Scenario: A link is delivered exactly once
- **WHEN** a single event link is opened, in either case
- **THEN** `SnapSyncRoot.onOpenUrl(_:)` is invoked exactly once for it

#### Scenario: The log names which hook the platform invoked
- **WHEN** an event link is opened while the app is running
- **THEN** the entry recorded for it names the specific hook that received it, so a warm-delivery gap on
  one OS version is diagnosable from a dump rather than inferred

#### Scenario: The app registers no custom URL scheme
- **WHEN** the app's `Info.plist` is inspected
- **THEN** it declares no `CFBundleURLTypes`, so a retired `snapsync://` URL reaches nothing

#### Scenario: A link opened while the app is already running still arrives
- **WHEN** an event link is opened from another app — a messenger, Notes, or a browser's smart banner —
  while the app is running or suspended in memory
- **THEN** the complete URL, fragment included, reaches `SnapSyncRoot.onOpenUrl(_:)`, by whichever
  delivery hook the platform invokes

#### Scenario: The platform delivering twice provisions once
- **WHEN** the platform delivers the same opened link through more than one hook — measured on a cold
  launch as the scene delegate's connection followed ~130 ms later by SwiftUI's `.onOpenURL`
- **THEN** each delivery is logged under its own entry-point name, and the link is acted on exactly
  once (capability `event-link`)

#### Scenario: A delivery hook is present for both machineries
- **WHEN** the Swift shell is inspected
- **THEN** it installs a scene delegate handling the connection and continuation callbacks, AND
  declares `.onOpenURL` on the `WindowGroup`, so neither machinery is relied on alone
