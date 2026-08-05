## Why

Every OS-supplied completion handler in the app is released in tens of milliseconds while the work it
triggered runs for tens of seconds. iOS takes that as "I am done, suspend me" — so the process is frozen
mid-flight, with photos staged but not imported and the controller mutex still held.

Measured on build 542 (iPhone11,2 / iOS 18.7.9, Bugsink `SNAPSYNC-6`, 2026-08-01):

| receipt | released after | work it triggered actually ran until |
|---|---|---|
| `← onSilentPush` 09:01:08 | 18 ms | 09:01:49 (41 s) |
| `← onSilentPush` 09:03:37 | 38 ms | 09:06:27 (169 s) |
| `← runDownloadBackstop` 11:44:23 | 48 ms | 11:45:18 (55 s) |
| `handleBackgroundUrlSession` 09:02:07 | ~0 ms (before any import ran) | 09:03:37+ |

The consequences are all in the same dump. A batch of five foreign photos woke the app at 09:02:07; four
imported in 1.3 s, the app was frozen ~1 s into a ~30 s budget, and the fifth never imported — it was
still suspended inside `performChanges`, holding `DownloadController.mutex`, when the process ended three
minutes later. Separately, **5 of 9** background union fetches died: every fetch that ever answered did so
in ≤1.7 s, while every failure reported a 1–27 minute "socket timeout" that is really the distance to the
next OS wake. Photos therefore arrive on some later wake instead of on the push that announced them, and
an import interrupted between its PhotoKit commit and its `markImported` write is the window the duplicate
photos fall through.

Nothing here is a slow PhotoKit call or a bad network. It is the app volunteering to be suspended with
work outstanding.

## What Changes

- **Receipts are held until their work completes.** Every OS-supplied completion handler —
  `handleEventsForBackgroundURLSession` (both sessions), both `BGTask` `setTaskCompleted` calls, and the
  silent-push handler — is released only after the work that wake triggered has finished or a deadline has
  fired. Releasing early stops being expressible: the handler is wrapped in a type whose only release path
  takes the work as a `suspend` block.
- **Trigger flows can no longer detach.** `flow/` classes lose their `CoroutineScope` constructor
  parameter and their non-suspend `() -> Unit` effect lambdas; `run()` becomes `suspend` and internal
  `scope.launch { … }` becomes `coroutineScope { launch { … } }`, preserving today's concurrency while
  making `run()` return only when its children finish. A flow with no scope and no fire-and-forget lambda
  cannot detach work behind the receipt's back. This also removes a latent race: `refreshAttestation` is
  currently launched *before* the union fetch, so the fetch can go out carrying the stale token it is
  concurrently replacing.
- **Staged-import work is tracked.** `QueuedPhotoDownloadJobs` takes a `suspend` `onStaged` callback,
  owns the launch, and joins the outstanding imports before releasing the download session's receipt.
- **Two bounds, so a held receipt can never become a termination.** A deadline on each import await —
  safe because `performChanges` does not block its caller, so cancelling frees a continuation, not a
  thread — which also releases `DownloadController.mutex`; and a per-entry-point deadline on the receipt
  itself for aggregate overrun. On an import deadline the drain stops for that wake rather than
  proceeding, because the one observed hang was environmental, not a property of the photo.
- **The HTTP client gets an explicit timeout.** `darwinHttpClient` configures none today, which is how a
  fetch reached 27 minutes. A short ceiling converts every one of those into a fast, honest failure.
- **The log becomes able to answer this class of question.** Millisecond timestamps, an explicit line
  whenever either deadline fires, and tracing of the PhotoKit change block and completion handler.
  A bespoke suspension detector was considered and dropped — see design.md D12; after this change a
  fetch reporting minutes against a 5 s ceiling already *is* the suspension signal.
- Two `:test:architecture` gates make the wrong shape a build failure.

Not in scope, deliberately: work with no receipt behind it (a foreground import that continues after
backgrounding). No `beginBackgroundTask`. And an import abandoned at its deadline may still commit,
leaving a library asset with a non-`IMPORTED` row — that is the same write-split hazard
`fix-duplicate-import-on-restart` already owns, and this change only logs it.

## Capabilities

### New Capabilities

None. The invariant belongs to the existing OS-boundary contract.

### Modified Capabilities

- `ios-app-shell`: every OS-supplied completion handler is released only after the work that wake
  triggered completes or its deadline fires; per-entry-point receipt deadlines; the download `BGTask`
  registers an expiration handler; the shared HTTP client carries an explicit request timeout;
  attestation refresh is awaited rather than launched.
- `module-architecture`: a new zone law — `flow/` classes hold no `CoroutineScope` and take no
  non-suspend effect lambda, so a trigger flow cannot outlive its own `run()`.
- `architecture-guards`: the two gates that enforce that law.
- `photo-download`: each import await is bounded; a deadline stops the drain for that wake; the staged
  resources delivered by one background-session wake are imported before that wake's receipt is released.
- `diagnostic-logging`: millisecond timestamps, deadline-fired lines, and change-block/completion
  tracing.
- `architecture-diagrams`: the closed flow grammar names the awaited `coroutineScope` fan-out and no
  longer sanctions an escaping `scope.launch`, so the transcriber refuses the detaching form rather
  than rendering it. Discovered while running `architectureDiagrams`, not planned.
- `harness-world-model`: `onStaged` is now a suspend seam the feature tracks, so the world no longer
  re-installs it — the spec's statement that it "is not a suspend seam" and its permission to shadow the
  composed hook are both withdrawn. Surfaced by the archive flow's delta-completeness gate, not planned:
  `test/world` was the one touched module with no capability accounted for.

## Impact

- `:domain` — `model/` gains the receipt type (beside `Logger.invocation`, so it is covered by
  `commonTest` on JVM and simulator, which `:app:ios` is not by rule). `flow/` loses scopes and
  non-suspend effect lambdas across `Foreground`, `Provision`, `SilentPush`, `DownloadBackstop`
  (`Background` already has neither). `feature/download` gains job tracking and the drain circuit-break.
  `compose/` loses its fire-and-forget adapter callback.
- `:adapter:ios:app-only` — the import await deadline and change-block/completion tracing.
- `:adapter:ios:ext-safe` — the HTTP request timeout and millisecond log timestamps.
- `:app:ios` and `iosApp/` — the four receipt call sites, and a `BGTask` expiration handler that does not
  exist today.
- `:test:architecture` — two new gates.
- **Observable behaviour change:** many `debug.log` durations become honest and much larger
  (`← onSilentPush (18ms)` is a lie today). A casual read of the next dump will look like a regression.
- **Risk to watch:** iOS penalises late receipts, not only unanswered ones. Typical held spans are ~1.5 s
  against a ~30 s budget, but whether this changes wake frequency is only observable over days on a real
  device; the deadline-fired lines are the detector.
