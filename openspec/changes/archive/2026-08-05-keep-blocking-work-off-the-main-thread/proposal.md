## Why

Whether a blocking platform call lands on the main thread is currently a property of **who called it**,
not of the call itself — so it cannot be judged by reading the file that makes it. The record shows the
consequence: of the 23 iOS adapter files that touch a blocking platform API (PhotoKit, Keychain,
`NSFileManager`, `NSKeyedArchiver`), **21 have no dispatcher hop**, and the two that do were both written
reactively, one per incident. One of those incidents is a recorded forcing proof — build 521 died on an
iPhone11,2 / iOS 18.7.9 with `assetsd` wedged inside `fetchPersistentChangesSinceToken`, 0.071 s of app CPU
across the whole watchdog allowance (`IosDiscovery.kt`).

The existing law — *"sync-I/O port impls own their dispatcher hop"* — is correct and unenforceable. Its
compliance test lives at the call site, in another module, in a different process. A reviewer of
`IosAlbumManager` (synchronous PhotoKit XPC, no hop, reached from the app's `Dispatchers.Main` scope on
every upload cycle and every status refresh) has no way to see the defect.

This change moves the decision to the one place that knows the answer — the composition root — so a
blocking call is off the main thread by construction rather than by each adapter author independently
rediscovering the rule.

## What Changes

- **The app scope stops being the main thread.** `SnapSyncRoot`'s process-lifetime scope moves from
  `Dispatchers.Main` to a **dedicated single thread** of its own. Single, not a pool:
  `Dispatchers.Main` is single-threaded and code in the scope relies on that for mutual exclusion — two
  places say so in comments, and the ones that don't say so cannot be enumerated. A one-thread context
  changes exactly one property (which thread) and leaves concurrency semantics untouched. Its own
  thread, not a slice of `Dispatchers.Default`, because Orbit's state reduction runs on `Default` and a
  blocked platform call must not eat that pool. (`Dispatchers.IO` would have been the obvious home and is
  `internal` on Kotlin/Native — established by compile, see `design.md` D1.)
- **User taps get the same treatment.** Orbit launches an `intent { }` on `Dispatchers.Unconfined`, so a
  tap's synchronous prefix runs on the calling thread — the main thread — regardless of the scope. The
  command bundle is decorated in one place (`compose/`); that decoration gains the lane.
- **The tap decorator splits in two, with no default.** UI-lane commands (`share`, `requestAccess`,
  `openSettings`, `choosePhotos`) present system UI and must stay on main; the rest must not. Forcing the
  choice makes it visible where a twelfth command is added, and a forgotten choice does not compile.
- **`create` and `rename` stop detaching inside their features.** The launch moves into `compose/`, so
  the work rides the app scope's lane and — the reason this rides along rather than waiting — the tap's
  `Logger.invocation` line stops closing before the work starts. Today `← tap.create (1ms)` is logged
  against a multi-second backend mint: the same class of false duration the `hold-os-receipts` change
  just removed from the OS-callback side.
- **Two new mechanical gates**: the main-thread lane is contained to an allowlist of UIKit adapters, and
  every field of the command bundle must be built through one of the two decorators.
- **A fence, not a fix, for constructor I/O**: a detekt rule forbids blocking calls in property
  initialisers and `init` blocks under `adapter/ios/**`, with the one existing instance
  (`FileBackedConfigStore`) grandfathered and its reason recorded. Fixing that instance collides with the
  screen's "first frame derives from real values, never a placeholder" rule and is out of scope here.
- **The world harness composes on the production lane structure**, so its claim to be the real
  composition is true of threading and not only of the graph — and so it can verify this change headlessly.
- **Three comments are sharpened.** `IosDiscovery`, `PhotoKitCandidateSource` and `ClearRequested` assert
  that Kotlin/Native has no `Dispatchers.IO`. That is substantially right — it exists but is `internal` —
  so each is narrowed to say what is actually absent (a *public* API) and what would falsify it, and
  restated against the fact that keeping work off main is now the composition's job rather than theirs.

Not in scope, deliberately: the runtime port sentinel in the harness, and the constructor-I/O redesign.
Both are recorded in `design.md` with the reason.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `module-architecture`: the dispatcher law is inverted — it stops asking each sync-I/O port impl to hop
  and instead states that the live core's composition scope is never UI-bound, that the main lane is
  reserved for UIKit, and that a hop is a throughput opt-in rather than a safety requirement.
- `architecture-guards`: two gates added (main-lane containment; every command declares its lane) plus
  the initialiser fence.
- `full-stack-harness`: the harness composes the live core on the same lane structure the device shell
  uses, rather than on the UI thread.

## Impact

- `:app:ios` — the scope, and the three direct UIKit touches that currently free-ride on it being main.
- `:domain` `compose/` — the command bundle's decoration; the `create`/`rename` launches.
- `:domain` `feature/` — `CreateEvent` and `RenameEvent` lose their `CoroutineScope` and become suspend.
- `:adapter:ios:ext-safe`, `:adapter:ios:app-only` — comment corrections; the two hops retarget to `IO`.
- `:test:architecture` — two gates and the detekt rule.
- `:app:desktop` — the world harness's composition scope.
- No user-visible behaviour changes, no API changes, no dependency changes.

## Verification

Two halves, and only one of them is automatable — stated here so the tasks do not imply otherwise:

- The world harness driven headlessly covers the change's core risk (state produced off the UI thread,
  observed by Compose) over the real graph on the real lane structure.
- The UI lane cannot be covered by any test available here: its four commands are fakes on desktop, and
  exercising them on device needs taps, which need a signed WebDriverAgent this project does not have.
  It is an operator smoke test. Gate 2 exists to keep that manual surface down to eleven reviewable lines.
