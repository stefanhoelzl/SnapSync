## Context

Six TestFlight crash submissions (10 Jul 2026, one tester, iPhone XR / iOS 18.7.9, builds 286 and 293)
carry one signature. Only one shipped a symbolicated `.ips`; it is unambiguous:

```
CFNetwork  __NSURLBackgroundSession _downloadTaskWithTaskForClass:   ← throws NSException
SnapSync   IosPhotoDownloadJobs.pump          IosPhotoDownloadJobs.kt:91
SnapSync   IosPhotoDownloadJobs.enqueue       IosPhotoDownloadJobs.kt:70
SnapSync   DownloadController.reconcile       DownloadController.kt:44/54/70
libc++abi  std::terminate → abort             EXC_CRASH (SIGABRT)
```

The report's `Launch Time` (09:09:55) and `Date/Time` (22:03:04) are ~13 h apart: this is **not** a launch
crash. It fires on a download reconcile long into a resident process. One submission carries the tester's
comment *"Tried to sync"*.

**Mechanism.** `cancelAll()` (leave/switch) calls `session.invalidateAndCancel()`. Invalidation is
terminal: the `NSURLSession` is dead forever. But `session` is a `by lazy` that is never rebuilt, so the
next `reconcile → enqueue → pump` creates a download task on the corpse. Creating a task on an
invalidated session throws an ObjC `NSException`. **Kotlin/Native has no ObjC→Kotlin exception bridge** —
a Kotlin `try/catch` cannot see an `NSException` — so it unwinds into the C++ runtime's terminate handler
and aborts. The crash is *latent*: `cancelAll()` itself is safe (the queue is empty, so `pump()` breaks
before creating anything). The bomb goes off on the next reconcile that actually has something to fetch.

**Two structural facts made this inevitable.**

*The contract was silent, and its sibling's contract was wrong.* `photo-download` states nothing about the
download client's transfer/session lifecycle. The archived `2026-06-30-add-photo-download` decision record
shows the *intent* was task-level (tasks.md 7.2: "leave/switch cancels **in-flight transfers**"), but the
implementation had no task references to cancel — only an `inFlight` **counter** — so
`invalidateAndCancel()` was the only lever available. It was a shortcut, not a decision; no rationale for
it exists anywhere.

The archaeology then runs **code → spec**, not the other way round. Four days later
`ios-url-session-upload` (2026-07-04) wrote its `disable` bullet as *"invalidate/cancel the background
`URLSession`"* — canonizing the download shortcut into prose. But that change's implementation built an
`inFlight: HashMap<String, InFlight>` holding real task references (for D5's precise reconciliation), so
its `cancelAll()` naturally cancelled **tasks** and never invalidates. The upload tier is safe **by
accident of its implementer, not by contract** — and its own spec still instructs the crash. Since
`disable` (access revoked) is followed by `enable` (re-granted) → `start()` → new upload tasks, a literal
implementation of that bullet would crash the upload tier identically.

*The crashing class has no tests.* `FakePhotoDownloadJobs` substitutes the **entire** implementation, so
every test in `:capability:download`, `:test:world` and `:test:integration` is green while the only real
implementation is fatal. The seam is faked at too coarse a grain: the thing that broke is the thing that
got replaced. `IosPhotoDownloadJobs` lives in a capability module — satisfying the hard rule by
*placement* — while having zero *coverage*.

**Constraint that shapes everything below:** there is no way to ask a background `NSURLSession` whether
creating a task will throw. No `isValid`, no error-returning variant, and the exception it throws is
uncatchable from Kotlin. The only defense is to never let the session be invalid.

## Goals / Non-Goals

**Goals:**

- Remove the crash at its root: cancellation must never destroy the transport.
- Make the crash **impossible to reintroduce silently** — pin it with a `commonTest` that fails today and
  runs on JVM and `iosSimulatorArm64` on every build.
- Close the contract gap in `photo-download`, and disarm the landmine in `ios-url-session-upload`'s
  `disable` bullet before someone implements it faithfully.
- Bring the download client's cancellation in line with the upload client's already-correct pattern, so
  the two siblings stop diverging.

**Non-Goals:**

- **No ObjC/Swift `@try/@catch` bridge.** See D4.
- **No `useBackgroundSession` escape hatch for downloads.** See D5.
- **Not fixing `enableBackgroundUpload()`'s destructor behavior** on the app-driven tier (it wipes the
  ledger and discovery cursor on a mere join and never runs a cycle). Real, likely more damaging than this
  crash, and being investigated on its own branch — but a different capability, a different failure mode,
  and it needs its own device verification. Bundling it would make a crash fix unreviewable.
- No change to `DownloadController`, the `PhotoDownloadJobs` seam, or any harness/world fake.
- No change to what leave *means*: transfers are still cancelled, non-terminal rows still pruned.

## Decisions

### D1 — Cancellation is task-level. `invalidateAndCancel()` is banned outright.

`cancelAll()` cancels each outstanding download task and leaves the session alive. Invalidation is reserved
for what it is actually for — process teardown, or deliberately discarding a session to rotate its
identifier — neither of which occurs here.

Every argument for invalidating collapses under inspection:

| Argument | Why it fails |
|---|---|
| "Release the session/delegate; avoid a retain cycle" | Only matters when *discarding* the object. It is held for the process lifetime. |
| "Guarantee no callbacks arrive after cancel" | Task cancellation delivers `didCompleteWithError(cancelled)` then silence. Sufficient. |
| "Purge the OS-side transfer state in `nsurlsessiond`" | Cancelling each task does exactly this. Equivalent. |
| "Leave/revoke is terminal — we'll never transfer again" | **False**, and it backfires: re-join / re-grant needs a live session. |

This is not a new pattern — it is `IosUrlSessionUploadPlatform.cancelAll()`, which has shipped correctly
since 2026-07-04. It also restores that change's own decision **D5**, whose whole point was that a
background `URLSession` *can* enumerate its tasks and should therefore act on them **precisely** instead of
performing blanket teardowns. `invalidateAndCancel()` is the blanket teardown D5 rejected.

**Alternative rejected — rebuild the session after invalidating.** Keep `invalidateAndCancel()` but null the
holder so the next pump constructs a fresh session. This *appears* to work but loses a race: invalidation
completes asynchronously, and a `pump()` landing before `didBecomeInvalidWithError` fires would create a
task on a still-invalidating session and crash anyway. It also forces identifier rotation (a background
identifier cannot be reused while the old session is tearing down), which in turn breaks
`handleEventsForBackgroundURLSession` re-adoption unless the generation is persisted. Not invalidating
sidesteps the entire problem.

**Alternative rejected — `getAllTasksWithCompletionHandler` instead of a task map.** Tempting (no
bookkeeping), but it is asynchronous, and `cancelAll()` is a `suspend` fun invoked from
`DownloadController.onLeaveOrSwitch()` **inside the controller's `Mutex`**. `IosUrlSessionUploadPlatform`
documents that on the iOS simulator *"getAllTasks never calls back"* — so a `suspendCoroutine` around it
would hang forever, never release the mutex, and deadlock **every future reconcile**. That converts a loud
crash into a silent permanent hang, which is worse. The task map is synchronous and avoids the callback
entirely.

### D2 — Split at a `Transport` seam; the logic moves to `commonMain` and gets tested.

The root cause of the *bug* is `invalidateAndCancel()`. The root cause of the *bug shipping* is that
`IosPhotoDownloadJobs` was structurally untestable, so nobody tested it. D1 alone fixes the former and
leaves the latter intact.

```
  BEFORE
  DownloadController ──[ PhotoDownloadJobs ]──▶ IosPhotoDownloadJobs ──▶ NSURLSession
                              ▲                          ▲
                     FakePhotoDownloadJobs        NOTHING TESTS THIS
                     (replaces EVERYTHING)        queue · window · codec · cancel lifecycle

  AFTER
  DownloadController ──[ PhotoDownloadJobs ]──▶ DownloadJobs (commonMain) ──[ Transport ]──▶ NSURLSession
                                                        ▲                         ▲
                                               UNDER TEST (JVM + sim)        FakeTransport
```

| Moves to `commonMain` (tested) | Stays in `iosMain` (thin edge) |
|---|---|
| queue + bounded in-flight refill window (`MAX_IN_FLIGHT`) | `NSURLSession` construction/config |
| `taskDescription` codec (3-part `\n` encode/decode) | `downloadTaskWithURL` / `resume` / `cancel` |
| staging-path computation (`/` sanitization) | `NSFileManager` move-to-staging |
| URL validity guard | delegate callbacks (forwarded inward) |
| **cancellation lifecycle — cancel tasks, never destroy the transport** | |

The regression test that would have caught this becomes three lines and runs on every build, no device
needed: *a `FakeTransport` that throws if a task is created after the transport was destroyed → assert
`cancelAll()` then `enqueue()` still creates tasks.* It fails against today's behavior.

**Alternative rejected — D1 only ("minimal").** Fixes the crash, leaves the class untestable and the next
regression undetectable. The whole reason we are here is that a fake at the wrong grain hid a fatal
implementation.

### D3 — A system-invalidated session self-heals.

D1 removes the only invalidation *we* control — 100% of the observed crash. iOS can still invalidate a
background session on its own (`didBecomeInvalidWithError` with a non-nil error, e.g. `nsurlsessiond`
failing to restore it). The delegate today implements no such callback, so that session would stay dead and
the next task creation would abort identically.

The delegate implements `URLSession(_:didBecomeInvalidWithError:)`; the transport drops its session, and
the next `createTask` rebuilds it with the **same stable identifier** — legal precisely because the callback
only fires once the old session is fully torn down and the identifier is free. The identifier must stay
stable for `handleEventsForBackgroundURLSession` re-adoption, so it is not rotated.

This is cheap *because* we took D2: the transport is now the only thing that knows a session exists, and the
fake can model invalidation, so the self-heal is itself testable.

### D4 — No ObjC `@try/@catch` bridge. The residual race is accepted and documented.

D3 is mitigation, not a guarantee. A `createTask` landing in the window between the system killing the
session and `didBecomeInvalidWithError` arriving still throws, still aborts. That window cannot be closed
from Kotlin; only a C/ObjC shim wrapping task creation in `@try/@catch` could.

Declined: it defends a failure mode **never once observed**; the upload tier has carried identical exposure
since 2026-07-04 without it; and it introduces an ObjC compilation surface to a project that deliberately has
none, in order to convert a rare abort into a silently-skipped download — which in a photo-sharing app may
not even be preferable. If it ever fires in the wild it will land in the same channel that caught this crash
(TestFlight `.ips` + `Documents/debug.log`), and we escalate then.

**Accepted risk, recorded so it is not rediscovered as a surprise.**

### D5 — No simulator escape hatch for downloads.

`IosUrlSessionUploadPlatform` takes `useBackgroundSession: Boolean` so a dev-forced run can use a
*foreground* session (the simulator cannot run background sessions). Giving `IosPhotoDownloadJobs` the same
flag was considered and rejected: the crash is thrown by `__NSURLBackgroundSession`, the **background**
subclass. A foreground session would very likely run straight through this defect, **manufacturing false
confidence**. Simulator integration fidelity cannot prove the property we care about; D2's `FakeTransport`
can. Downloads remaining inert on the simulator is a known, accepted limitation — the download dev loop is
`:test:world` and `:app:desktop:run`, not the simulator.

### D6 — Correct `ios-url-session-upload`'s `disable` bullet (spec-only).

Its `leave` and `re-provision` bullets already say "cancel in-flight **tasks**". Only `disable` says
"invalidate/cancel the background `URLSession`" — inconsistent with its siblings, contradicted by its own
change's D5, unimplemented by its own code, and a literal instruction to reintroduce this crash on the
upload tier. It is corrected to task-level language. **No upload code changes**; the contract is being moved
to where the implementation already, correctly, is.

The unifying rule both specs were groping at, now stated once:

> A background `URLSession` is a **process-lifetime singleton**. Cancellation is a **task-level** operation.
> **Invalidation is terminal** — creating a task on an invalidated session throws an ObjC `NSException`,
> which Kotlin/Native cannot catch and which aborts the process. Invalidation must therefore never be used
> as a cancellation mechanism. Every lifecycle verb — disable, re-provision, leave, switch — means *cancel
> the tasks*, never *destroy the session*.

## Risks / Trade-offs

- **[Residual race: system invalidation between the kill and the callback]** → Unclosable from Kotlin (D4).
  Never observed; identical exposure exists on the upload tier. Accepted and documented; escalate to an ObjC
  bridge only if it appears in a real crash report.
- **[`inFlight` bookkeeping must not drift]** → The count is now driven by the task map. Cancelled tasks
  still deliver `didCompleteWithError`, so `onComplete()` decrements and re-pumps as before; the queue is
  cleared first, so the re-pump finds nothing. A stuck count would throttle the refill window
  (`MAX_IN_FLIGHT = 24`), not crash. Covered by `commonTest` on the window logic.
- **[Refactor touches a live download path]** → `DownloadController`, the `PhotoDownloadJobs` seam, and every
  harness fake are unchanged; the split is internal to the implementation. The blast radius is one
  construction site (`SnapSyncRoot.kt:202`).
- **[The simulator still cannot exercise the real transport]** → Deliberate (D5). Correctness is pinned by
  `FakeTransport` in `commonTest`; the real background session is confirmed on device.
- **[`compileIosMainKotlinMetadata` is the only local iOS check]** → Kotlin breakage in `iosMain` is caught
  on Linux; the Swift host and real device behavior are confirmed on CI/device, per CLAUDE.md.

## Migration Plan

No data migration, no schema change, no persisted state touched. The background-session identifier is
unchanged (`app.snapsync.download.bg`), so a build carrying this fix re-adopts any session and transfers the
previous build left in flight.

**Device verification** (the property `commonTest` cannot prove): join an event with foreign photos, leave
it, then foreground / re-join and let a download reconcile run. Before this change that sequence aborts;
after it, downloads enqueue and stage. Confirm via `Documents/debug.log` (`pymobiledevice3 apps pull
app.snapsync Documents/debug.log`) — a `[reconcile]` span with enqueued downloads and no abort.

**Rollback:** revert the commit. Nothing outside `:capability:download` and one wiring line in `:app:ios`
changes, and no persisted state is altered.

## Open Questions

- Should the URL guard (D-scope: skip non-`http(s)`/hostless URLs) *also* mark the resource in the download
  store so a permanently-malformed URL is not retried on every single reconcile? Current posture: log and
  skip, leaving it pending — consistent with `photo-download`'s "no terminal failure state" rule, at the cost
  of re-logging each cycle. Left as-is unless the union is found to emit such URLs in practice.
- Whether the `enableBackgroundUpload()` destructor defect (out of scope here) shares a root cause worth
  generalizing — i.e. whether a single "lifecycle verbs never destroy transports or state they will need
  again" principle should be lifted into both tiers' specs once that investigation lands.
