## Why

**A user-reported bug could not be diagnosed from a diagnostic dump, because the seam that failed records nothing when it declines to act.**

Bugsink `SNAPSYNC-3` (iPhone XS, iOS 18.7.9, 0.2 build 542): switching events required force-quitting the app. The dump proves the second event link never reached the join gate — the new event id appears nowhere in a four-day log before the cold relaunch, and every `onOpenUrl` in that log shares its second with an `=== app process start ===`. But it cannot say **why**, because `SnapSyncRoot.onUserActivity` forwards to `forwardEventLink` (`model/UniversalLinkActivity.kt`), which answers a three-state question — *forwarded* / *not a browsing-web activity* / *no webpage URL* — with `String?`, and drops the last two in silence. So "iOS never called us" and "iOS called us and we discarded it" are **byte-identical in every dump**, and the only evidence that the gate never opened was the absence of an unrelated HTTP request.

That shape is not new here, and the codebase already refuses it everywhere else. `ConfigFileRead` admits *only* not-found as absence and defers on every other error, with the reason written down: *"Admitting an unknown error into the absent class would recreate the false-leave bug this whole seam exists to prevent."* `ConfigRead` carries sentinels `-1`/`-2` purely so a device log can tell two unreadables apart. `JoinLoad` keeps `NotFound` distinguishable from `Failed`. `SwitchDecision` returns a named answer instead of a null. `KeychainRead` separates `Absent` from `Unavailable(status)` and `readExisting` **throws** on the latter — *"never mistaken for absence."*

The rule is real, it is load-bearing, and it has never been written down. So it stops at the door, and three separate silences were found in one afternoon of reading:

1. `eventLinkFromUserActivity` — three states collapsed to two, no consequence stated anywhere; the reported bug.
2. `UploadExtensionRoot.attestToken()` — `runCatching { … }.getOrNull()` justified for `errSecInteractionNotAllowed` (*"a null token is a 401, which is retryable"*) while also absorbing `errSecMissingEntitlement (-34018)`, which is permanent, not retryable, and produced the *"dead in the water"* incident of 2026-07-21. The status code the port went to trouble to preserve is discarded unlogged, in the process whose log is hardest to read.
3. `BackgroundUploadExtension.notifyTermination()` — the OS announcing it is **killing the upload cycle**, forwarded nowhere. A terminated `process()` appears in `ext-debug.log` as an enter with no exit and no explanation.

All three are the same defect: **a justified no-op that is also silent.**

## What Changes

- **A new law, `Absence is never silent`**, added to `module-architecture` and its CLAUDE.md digest. It describes existing practice (five conforming seams cited) and finds the violations above. Its absolute clause is the entry point: a lost trigger is invisible and unfixable, a spurious log line is harmless and visible — the same asymmetry argument the selection policy already uses to admit on doubt.
- **Every platform entry point logs its raw inputs before any decision and names its outcome on exit.** The population is **derived, never hand-enumerated** (three rules; see design), honouring the existing `Commands cross one door` requirement. Hand-enumeration was tried during design and was wrong in both directions.
- **Entry-point filters return named outcomes.** `eventLinkFromUserActivity` gains a sealed result; the silent-`null` shape stops existing at the door.
- **The UI door is scoped.** `UserCommands` is decorated at its single `compose/` construction site with `tap.*` entry logging, so every line in `debug.log` traces to either an OS callback or a named tap. This is the cheapest fix in the change and it erases the single most expensive ambiguity in the `SNAPSYNC-3` investigation: proving the 08:49:56 leave was a manual tap rather than the switch path required reading two source files.
- **A nullable-seam inventory** over `ports/` + `model/` + the composition roots, audited once and then pinned: population derived, verdict hand-written (one line naming the consequence that makes the collapse safe). A new nullable port seam is a red build until someone states its consequence.
- **Three guards**: the derived entry-point guard, the nullable-seam inventory guard, and a `SwiftShellGuardTest` body rule requiring every Swift OS callback to reach Kotlin — which is what catches `notifyTermination` and the `NSLog`-only `didFailToRegisterForRemoteNotificationsWithError` (os_log redacts interpolated `NSLog` to nothing, so that failure path is invisible today).
- **The three silences above are fixed.**
- **Each delivery hook forwards under a distinct entry-point name** (`onLaunchActivity` cold, `onSceneContinueActivity` warm), so a dump names which hook the platform actually invoked. This is what makes the reporter's next dump decisive about `SNAPSYNC-3` without anyone guessing.
- **A candidate fix was attempted, measured, and reverted inside this change.** A second warm hook (SwiftUI's continuation modifier) was added on the theory that July's device matrix showed it working warm. On device it fired **0 times in 8 warm deliveries**: a scene has exactly one delegate, this app installs its own for the cold path, and SwiftUI's — which feeds that modifier — is therefore never created. July's rows are mutually exclusive configurations, not composable features. The hook and its duplicate-suppressor are removed; the reasoning and the measurement are kept in `design.md` so nobody re-tries it. **This change therefore does not fix `SNAPSYNC-3` — it makes the next report answer it.**

Deliberately **not** in scope: a runtime "entered without an entry scope" assertion. It was designed and rejected — with the population rule-derived, its only unique coverage was the residue, and at the core-command boundary it would fire on every user tap. The same effort went to the UI door instead, which is the actually-uncovered surface.

No member-visible behavior changes. This is diagnosability and a stated law: the reported bug is not fixed here, because no fix for it has been measured to work anywhere.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `module-architecture`: adds the law **Absence is never silent**, and its one-line digest entry.
- `diagnostic-logging`: entry points log raw inputs before deciding and name their outcome; severity policy (`Info` for OS-event entries, `Debug` for per-item ones) so a 200-photo import cannot flush the ~100-breadcrumb Sentry window; UI taps carry a `tap.*` context.
- `ios-app-shell`: each delivery hook forwards under a distinct entry-point name; the exactly-once requirement gains the measurement showing *why* warm hooks cannot simply be stacked.
- `architecture-guards`: the derived entry-point guard, the nullable-seam inventory guard, the Swift shell body rule, and the delivery-seam guard narrowed to the two hooks that exist, carrying the measurement in its failure message.

## Impact

- **Code**: `iosApp/iosApp/iOSApp.swift`, `iosApp/BackgroundUploadExtension/BackgroundUploadExtension.swift`, `app/ios/.../SnapSyncRoot.kt` (the `log.invocation` wrap moves up from `LiveShell`/`ForgeShell` onto the entry points), `app/ios/.../MainViewController.kt`, `app/ios/extension/.../UploadExtensionRoot.kt`, `domain/model/UniversalLinkActivity.kt`, `domain/compose/SnapSyncApp.kt` (the `UserCommands` decoration), `adapter/ios/app-only` (the three ObjC-protocol conformances), `test/architecture`.
- **No** backend, storage, upload, or download path changes. The dev `SNAPSYNC_EVENT_LINK` trigger is untouched (it calls `onOpenUrl` directly and never crosses this seam).
- **Sentry/Bugsink**: nothing to build — `SentryLogWriter` already turns every Kermit line at Warn-and-below into a `[<entryPoint>]`-prefixed breadcrumb, so the new lines ride into every event automatically. The severity policy exists to protect that window.
- **Sequencing**: the fix was attempted here and reverted on evidence (see `design.md` D12). What ships is the instrumentation, the law, the guards, and the tap decoration. The next `SNAPSYNC-3`-shaped dump names the cause, and the next lead — `scene(_:willContinueUserActivityWithType:)` — is recorded in Open Questions for whenever an iOS 18 device is available. (The empty `2026-08-01-restore-warm-event-link-delivery/` scaffold is removed — it was named for a cause that has never been measured.)
- **Accepted cost**: `LogContext` is a process-global whose "outermost wins" mislabeling was justified by *"iOS delivers app entry points serially per process."* UI taps are not serial with background work, so a tap landing inside an in-flight cycle inherits the cycle's label. It degrades onto the tap, not the cycle. Named here rather than inherited silently — which is the law this change is adding.
