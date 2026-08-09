## Why

`ios-url-session-upload` and the code have contradicted each other since the tier shipped. The spec says
**twice** that a background `URLSession` runs in the simulator (lines 16 and 350); `IosUrlSessionUploadPlatform`
says the opposite in a comment ("the iOS **simulator** does not support background NSURLSession — `getAllTasks`
never calls back and transfers never run") and downgrades the simulator to a foreground session on the strength
of it. Neither claim carried a forcing proof, which is precisely what `module-architecture` forbids: *"a
platform-capability claim is settled by a compile, not by a symbol table"*, and *"necessity claims carry forcing
proofs — an API contract, a measurement, or a vendor doc, never the current code."*

It has now been measured. The comment is wrong: a background-configured session on `iosSimulatorArm64` answers
`getAllTasksWithCompletionHandler` and executes an `uploadTaskWithRequest(…, fromFile:)` through to
`didCompleteWithError`. The downgrade defends nothing, and the runtime environment read that drives it
(`SIMULATOR_DEVICE_NAME`) is redundant with the compilation target in the first place.

## What Changes

- **Delete the simulator transport downgrade.** Every process — device and simulator — uses a background
  `URLSession`, which is what the spec has always said. `OsFacts.isSimulator`,
  `CompositionMode.Live.useBackgroundSession`, the `SnapSyncRoot` cast that reads it outside the one
  `when (mode)` switch, both constructor flags (`UrlSessionUploadController`, `IosUrlSessionUploadPlatform`)
  and the single `if` in the `session by lazy` all go.
- **Delete `OsFacts`.** With `isSimulator` gone it carries one field, so the resolver takes
  `backgroundUploadSupported: Boolean` directly: `resolveComposition(directives, backgroundUploadSupported,
  isForgeState)`. `CompositionMode.Live` becomes `Live(tier)`.
- **Correct the false comment**, narrowly. The corrected text states only what was measured — tasks execute and
  `getAllTasks` calls back — and leaves **app relaunch** for `handleEventsForBackgroundURLSession` explicitly
  unproven, because the probe host was an `xctest` process that stayed alive and could not exercise it.
  Replacing one overclaim with another is the failure mode this change exists to end.
- **Supersede — do not edit — `fix-download-session-lifecycle`'s archived D5.** Its parenthetical "(the
  simulator cannot run background sessions)" is measured false, but the archive stays untouched and this
  change's decision record carries the correction, exactly as `2026-08-06-correct-limited-access-read-premise`
  superseded the 2026-07-20 alert-storm claim (still verbatim in its archive). The record carries three facts,
  not one: the parenthetical is false; D5's **decision** stands regardless, resting on the
  `__NSURLBackgroundSession` subclass argument the probe does not touch; and D5's closing "downloads remaining
  inert on the simulator" is now **unproven**, to be settled where a simulator host and a local backend exist.
- Drop `CompositionModeTest`'s simulator-downgrade case; it pinned a fact the resolver no longer owns.
- Unrelated, riding as its own commit: correct `test/world/build.gradle.kts`'s claim that JUnit 4 is "the
  framework every jvm test task in this build runs on" — `:test:architecture` and `:tools:diagrams` both use
  `useJUnitPlatform()`.

No shipped device binary changes behaviour: a device already used a background session before and after. The
simulator's transport changes, which is why this is a spec change and not a refactor.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-url-session-upload`: the requirement "The tier-force flag alters neither transport nor tier exclusivity"
  currently mandates *"Whether the process is running on a **simulator** SHALL be derived from the environment,
  never inferred from the tier-force flag."* That sentence loses its subject — no simulator determination
  survives anywhere — so it is removed and the surrounding requirement restated as one transport for every
  host. Lines 16 and 350 are **unchanged**: they were right.
- `module-architecture`: "One shared composition" describes composition selection as "a pure, unit-tested
  function from parsed launch directives and **OS facts**". Deleting the `OsFacts` type makes that wording
  false; it becomes the OS capability the resolver actually reads.
- `ios-app-shell`: "iOS live composition root" names `OsFacts` as the resolver's second input. Same
  deletion, same falsehood. Found by the archive's **dead-types gate**, not by the impact analysis —
  this is a spec the change never otherwise touches, which is precisely the reach that gate exists for.

## Impact

- `:domain` `model/` — `CompositionMode.kt` (`OsFacts` deleted, `Live` narrowed, `resolveComposition` signature),
  `commonTest`'s `CompositionModeTest`.
- `:app:ios` — `SnapSyncRoot` (the `NSProcessInfo` environment read, the `osFacts` construction, the
  `(mode as CompositionMode.Live)` cast at the `UrlSessionUploadController` call site).
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform` (flag, field, `if`, and the comment).
- `app/ios/src/…/UrlSessionUploadController.kt` — the threaded flag.
- `:test:architecture` — `PlatformIdentifierTest`'s accepted-exception entry, whose reason names "a total
  function over `OsFacts`".
- `architecture/` — `di.md` lists `OsFacts`; `./gradlew architectureDiagrams` must be re-run and committed
  (the `diagrams` check is required and a stale tree blocks the PR).
- `:domain` `compose/` — `SnapSyncApp.kt`'s comment referencing `OsFacts` as a value shape.
- Docs — `app/ios/CLAUDE.md`'s two-upload-tiers section states simulator-ness is read from `SIMULATOR_DEVICE_NAME`.
- Archived decision records — **none edited**. `changes/archive/2026-07-12-fix-download-session-lifecycle`'s D5
  is superseded by this change's `design.md`, per the repo's existing precedent.
- No dependency, API, or backend impact. Changelog label: `internal`.
