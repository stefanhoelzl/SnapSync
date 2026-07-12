## Why

Six TestFlight crash reports (10 Jul 2026, builds 286/293, iPhone XR / iOS 18.7.9) are all the same
`EXC_CRASH (SIGABRT)`: `IosPhotoDownloadJobs.cancelAll()` calls `session.invalidateAndCancel()`, which
**permanently** kills the background `URLSession`. The session is a `by lazy` that is never rebuilt, so
the next download reconcile (foreground, push, or re-join) creates a task on the dead session.
Creating a task on an invalidated `NSURLSession` throws an Objective-C `NSException` — which
Kotlin/Native cannot catch — so it reaches `std::terminate` and aborts the process. Leave the event,
then download anything, and the app dies.

Two structural facts let this ship, and both must be closed or it comes back:

1. **The contract was silent.** `photo-download` states no requirement about the download client's
   transfer/session lifecycle, so the implementation invented one. Worse, `ios-url-session-upload`'s
   `disable` bullet actively *instructs* "invalidate/cancel the background `URLSession`" — a latent
   landmine the upload implementation quietly ignores (it cancels tasks). Had anyone followed that
   bullet literally, the upload tier would crash identically on revoke→re-grant.
2. **The crashing class has no tests.** `FakePhotoDownloadJobs` replaces the *entire* implementation, so
   `IosPhotoDownloadJobs` — its queue, bounded in-flight window, and cancellation lifecycle — has zero
   coverage in a capability module whose purpose is that logic lives where it can be tested.

## What Changes

- **Cancellation becomes task-level, never session-level.** `cancelAll()` cancels the outstanding
  download tasks and leaves the session alive and reusable. `invalidateAndCancel()` is removed. This is
  the pattern `IosUrlSessionUploadPlatform` already uses and proves.
- **The download jobs gain a `Transport` seam.** The queue, the bounded in-flight refill window, the
  `taskDescription` codec, the staging-path computation, and the cancellation lifecycle move to
  `commonMain` under test on JVM **and** `iosSimulatorArm64`. Only `NSURLSession`/`NSFileManager` calls
  stay in `iosMain` behind the seam. A `commonTest` regression — *cancel, then enqueue, and tasks are
  still created* — fails against today's behavior.
- **A system-invalidated session self-heals.** The delegate implements
  `URLSession(_:didBecomeInvalidWithError:)`; the transport drops the session so the next task creation
  rebuilds it (same stable identifier — legal once the callback has fired, and required for
  `handleEventsForBackgroundURLSession` re-adoption).
- **Unsupported download URLs are skipped, not fatal.** A resource whose `url` parses but is not
  `http(s)` with a host is logged and skipped rather than handed to the session, closing the other route
  into the same uncatchable throw.
- **`ios-url-session-upload`'s `disable` bullet is corrected** to task-level cancellation. This is a
  **spec-only** correction: the upload code already cancels tasks and is unchanged.

## Capabilities

### New Capabilities

None. This change adds no capability; it closes a contract gap in two existing ones.

### Modified Capabilities

- `photo-download`: **new requirement** — the download client's transfer/session lifecycle. Cancellation
  (leave, switch, re-provision) SHALL cancel in-flight *tasks* and SHALL NOT invalidate the background
  session; a session invalidated by the system SHALL be rebuilt rather than reused; a resource whose URL
  is not a fetchable `http(s)` URL SHALL be skipped. Adds the testability requirement that this logic
  live behind a transport seam so it is covered in `commonTest`.
- `ios-url-session-upload`: **corrected requirement** — the "App-driven lifecycle" `disable` bullet
  changes from "invalidate/cancel the background `URLSession`" to cancelling in-flight tasks, matching
  the other lifecycle verbs (`re-provision`, `leave`) and the shipped implementation. No code change.

## Impact

- **`:capability:download`** — `IosPhotoDownloadJobs` splits into a tested `commonMain` orchestrator plus
  a thin `iosMain` transport. New `commonTest` coverage for the queue, window, codec, and cancellation
  lifecycle, including the regression that reproduces this crash.
- **`:app:ios`** — `SnapSyncRoot` composes the transport into the download jobs (wiring only, one
  construction site at `SnapSyncRoot.kt:202`). `DownloadController` and the `PhotoDownloadJobs` seam are
  **unchanged**, so `:test:world` and `:test:integration` are unaffected.
- **No behavior change on leave.** In-flight transfers are still cancelled and non-terminal rows still
  pruned; only the *mechanism* changes (cancel tasks, keep the session).
- **Not in scope**: `enableBackgroundUpload()` behaving as a destructor on the app-driven tier
  (`urlSessionUpload.leave()` wiping the ledger and cursor on a mere join, and never running a cycle).
  That is a separate defect in `ios-url-session-upload`, under investigation on its own branch.
- **Verification**: the regression is provable without a device (`commonTest`, JVM + simulator). The
  session lifecycle itself is confirmed on device — join an event, leave it, foreground/re-join, and let
  a download reconcile run.
