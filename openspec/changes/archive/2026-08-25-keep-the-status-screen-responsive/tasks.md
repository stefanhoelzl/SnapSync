## 1. Container liveness (`:ui:presentation`) — first, because it is what makes §3's rethrow safe

- [x] 1.1 Add `onIntentError: (Throwable) -> Unit = {}` to `StatusContainerHost`'s constructor, beside the existing `log` seam, documented as the container's error seam: inert by default so harnesses and tests construct unchanged, bound by the composition to an `Error`-severity log (design D7).
- [x] 1.2 Pass `buildSettings = { exceptionHandler = CoroutineExceptionHandler { _, t -> onIntentError(t) } }` to the `scope.container(...)` call, with a comment naming the measured library behavior it defends against: with no handler, `RealContainer` re-throws, cancelling the non-supervisor `intentJob` that parents every intent, after which no later intent runs for the life of the process (design D6).
- [x] 1.3 Add a `StatusContainerHostTest` liveness pin: an intent that throws, then a later intent whose state change is asserted to land, plus an assertion that the injected `onIntentError` received the throwable. This is the guard that makes an Orbit version bump fail the build rather than silently restore the bricking.
- [x] 1.4 Bind `onIntentError` in `SnapSyncRoot`'s `host` assembly to `log.e(t) { … }` so it reaches `debug.log` and the crash reporter. Wiring only — no conditional (`detektAppShell` gates that).

## 2. Drop the pending-switch suppression (`:ui:screens`)

- [x] 2.1 In `StatusScreen`, remove `!pendingSwitch &&` from the settings-action condition so the gear renders whenever the joined layer does and a membership is known.
- [x] 2.2 Remove `!pendingSwitch &&` from the `onEditHeading` condition so the rename pen renders wherever the heading does.
- [x] 2.3 Delete the now-unused `val pendingSwitch = …` local; the switch dialog reads `state.pendingSwitch` directly and is untouched.
- [x] 2.4 Rewrite the two rationale comments to state why the affordances are NOT suppressed: the race is already prevented by `ReconfigureEvent`/`RenameEvent`'s `eventId` guards and by the `LaunchedEffect(joined)` reset, and suppressing here also suppressed during a join's own commit.
- [x] 2.5 Invert the two `StatusScreenTest` suppression tests — `assertDoesNotExist()` → `assertExists()` — and rename them to say the affordances stay offered while a `pendingSwitch` is carried.
- [x] 2.6 Add a `StatusScreenTest` case for the reported shape specifically: `Joined` carrying `PendingSwitch(sameEventId, Committing)` renders both the gear and the pen.

## 3. No non-terminal resting phase (`:ui:presentation`)

- [x] 3.1 Wrap `commit()`'s `commands.commitJoin(...)` call so a throwable repairs the phase before propagating: `catch (cancelled: CancellationException) { throw cancelled }` first (the `LedgerCountsPoller` shape), then, only while the pending join is still this one, clear it when `config.value?.eventId == p.eventId` and otherwise set `CommitFailed`, and rethrow (design D3, D5).
- [x] 3.2 Wrap `loadInto()`'s `loadJoinDetails(...)` call the same way, setting `LoadFailed` and rethrowing — covering both `startPending` and `onRetryLoad`.
- [x] 3.3 Document 3.2 as defence-in-depth and unreachable **in production** (`HttpEventDirectory.fetch` is `runCatching { … }.getOrDefault(Failed)` and `toJoinLoad` is pure). Corrected while implementing: unlike the `BackgroundUploadPump` precedent it is NOT untestable — `loadJoinDetails` is a constructor seam, so a throwing loader can be injected and the branch is covered in 3.4 (design D4).
- [x] 3.4 Add `StatusContainerHostTest` coverage: a throwing `commitJoin` with the config already naming the joined event → the pending join is cleared and the state is `Joined`; a throwing `commitJoin` with the config still absent → `JoiningEvent` in the commit-failure phase with its Retry; a throwing `loadJoinDetails` → the load-failure phase.

## 4. Verify

- [x] 4.1 `./gradlew build` — the full check, including `:ui:screens:jvmTest`, `:ui:presentation:jvmTest`, the `:test:architecture` gates, and `detektAppShell`.
- [x] 4.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets, since `SnapSyncRoot` changed.
- [x] 4.3 Confirm the new `commonTest` cases are picked up on `iosSimulatorArm64` in CI — that run is what settles the Orbit liveness behavior on the iOS klib, which was measured only on the JVM artifact. Verified as far as Linux allows: all new cases are in `commonTest`, and `.github/workflows/ios.yml` runs `./gradlew iosSimulatorArm64Test` as a parallel merge gate. The simulator run itself happens on CI.
- [x] 4.4 Drive the joined screen in the world harness (`:app:desktop:run`) or headlessly via `ui-harness`, joining an event and confirming the gear and pen are present from the first joined frame rather than appearing after the provision completes. Done via `driveWorld`: created and joined "Uropa's 100th" through the real create → gate → commit path; the joined screen renders `Rename event`, `Event settings`, `Share invite link` and `Leave event`.
- [x] 4.5 `npx --yes @fission-ai/openspec@1.5.0 validate keep-the-status-screen-responsive --strict`.
