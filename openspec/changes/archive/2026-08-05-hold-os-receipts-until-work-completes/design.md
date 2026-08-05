## Context

iOS hands the app a **receipt** at each background wake — a completion handler for
`handleEventsForBackgroundURLSession`, `BGTask.setTaskCompleted`, or the silent-push handler. Calling it
declares "I am done"; the system may then suspend the process. SnapSync calls all of them within tens of
milliseconds of the wake, while the work that wake triggered has only been *queued*.

The shell already looks correct:

```kotlin
scope.launch { try { app.silentPushFlow.run(userInfo) } finally { completion() } }
```

but `SilentPush.run()` and `DownloadBackstop.run()` are non-suspend and `scope.launch { … }` internally,
so `run()` returns as soon as the inner coroutine is queued. Exactly one receipt in the app is correct
today — `UrlSessionUploadController.onBackgroundTask`, which awaits a genuinely `suspend` call
(`← url-session.onBackgroundTask (989ms)` in the field log, a real await).

Everything below is grounded in one dump: Bugsink `SNAPSYNC-6`, event
`c3bfecf2-95ba-4015-a85d-3bdf3f1215bf`, release 0.2 / build 542 (commit `f936b9fc815a`), iPhone11,2 /
iOS 18.7.9, production, `url_session` tier, 2026-08-01. The import path in that build is
byte-identical to today's apart from `PHAssetResourceCreationOptions.originalFilename`.

**Forcing proof — the process was suspended, not blocked.** Three invocations show a long gap with zero
log output, and **every gap ends exactly at an OS-delivered wake**: 89 s ending at `onSilentPush`, 400 s
and 1817 s ending at `runDownloadBackstop` / `runUploadHeartbeat`. A blocked main thread would not silence
the URLSession delegate queue or the log writer. The decisive line is
`← platform.discoverResources = 10 candidate(s) (6241150ms)` — 104 minutes, on a call that hops to
`Dispatchers.Default` and therefore cannot be blocking main. Only whole-process suspension explains all
of them. Expires if iOS ever stops freezing suspended processes.

**Forcing proof — the receipt is why suspension came so early.** At 09:02:07 the app woke for background
session events, completed four imports by ~09:02:08, and was frozen roughly one second into a budget
commonly cited as ~30 s. A budget expiring does not look like that; a "done" declaration does.

## Goals / Non-Goals

**Goals:**

- No OS-supplied completion handler is released while work that wake triggered is outstanding.
- Releasing early is not expressible, and the wrong shape fails the build.
- A hung unit of work can never convert into a terminated app.
- The next field dump can answer this class of question by reading, not by deduction.

**Non-Goals:**

- Preventing suspension in general. iOS suspends and terminates for jetsam, budget exhaustion and
  expiration regardless of what we hold. This change stops us *volunteering*.
- Work with no receipt behind it — a foreground import that continues after backgrounding. No
  `beginBackgroundTask` assertion is introduced.
- Reconciling an import that commits after we stopped waiting. That is the same write-split hazard
  `fix-duplicate-import-on-restart` owns; here it is logged and handed over.
- The watchdog termination at 09:06:45 in the same dump (`fix-watchdog-termination`).

## Decisions

### D1 — The invariant is receipt discipline, not durability

Alternatives: (a) hold OS runtime whenever any work is outstanding, including a `beginBackgroundTask`
assertion for push- and foreground-triggered work; (b) accept suspension as normal and instead guarantee
every unit of work is restartable from durable state.

Receipt discipline is narrow, mechanically testable, and would have prevented the observed incident
outright — 1.3 s of work inside a ~30 s budget. (a) is a strictly larger surface for a failure mode not
yet observed. (b) overlaps another workspace and does not stop the app declaring itself done.

### D2 — Await the whole flow, not a narrower span

Two narrower designs were worked through and rejected **on measurement**.

*Await only the durable drain* — hold while `store.importableAssets()` is non-empty. Its premise survived
audit: tracing every two-step durable write, the import is the only one whose interruption corrupts
rather than costing a retry (an upload `REQUESTED` row with no job is re-requested; acks are staged
OS-side and drained next cycle; unstaged bytes are re-downloaded; a phantom `createdLocalId` only ever
suppresses a discovery match). It was rejected because it releases the receipt with the union fetch in
flight, and **5 of 9 background fetches died** that way. Every fetch that answered at all answered in
≤1.7 s; every failure reported a 1–27 minute socket timeout whose duration equals the distance to the
next wake. Holding ~0.4 s converts most of that mortality into photos arriving on the push that announced
them.

*Discriminate by "what the wake is for"* — hold the fast, progress-critical chain and re-arm a wake for
the slow resumable tail. This was justified by an estimate that the PhotoKit discovery walk costs ~10 s
(extrapolated from CLAUDE.md's 110 ms/asset for an *unbounded* walk). Measured, every real
`platform.discoverResources` in the dump is **≤574 ms**, median ~50 ms — the shipped code uses the
capture-date-bounded fetch plus the change feed and never touches the whole library. The discrimination
would have bought ~300 ms while costing a per-entry-point purpose classification that no gate can check
and that specs are where such judgements go to be forgotten.

With measured numbers only, a push wake holds ~1.5 s typical and ~15 s worst against a ~30 s budget.

### D3 — `flow/` loses both its scope and its non-suspend effect lambdas

Removing `CoroutineScope` alone is insufficient. `SilentPush` and `DownloadBackstop` also take
`reloadConfig: () -> Unit` and `refreshAttestation: () -> Unit`; the shell's `refreshAttestation` does
`scope.launch { … }` around a network call. The flow holds no scope, the zone gate is green, and the work
escapes anyway — a non-suspend lambda can *only* detach. Both become `suspend`.

This also removes a latent race that predates the change: `refreshAttestation` is invoked before the
fan-out is launched, so the union fetch can go out carrying the stale token that refresh is concurrently
replacing.

`scope.launch { X }` becomes `coroutineScope { launch { X } }`, which preserves the existing concurrency
in `Foreground` (three fan-outs) and `Provision` (three) while making `run()` return only when they
finish. `Background` already holds neither and is untouched.

### D4 — The receipt is a type in `model/`

Alternatives: a `ports/` seam, or keeping it in the shell.

`:app:*` Kotlin is wiring-only and untested by rule, and all four receipts land there — a receipt type
there is untestable by construction, and a `withTimeout` decision there is exactly what the shell gates
forbid. `ports/` inverts the direction: a receipt is an obligation we discharge, not a capability we call,
and features would gain a port nobody calls. `model/` already hosts `Logger.invocation`, a cross-cutting
behavioural helper over an injected seam — the same shape and the same reason. It is constructed at the
Kotlin edge in `SnapSyncRoot`; Swift keeps forwarding a raw `() -> Unit` and deciding nothing.

The type is the primary guard: if the only release path takes the work as a `suspend` block, early
release stops being expressible.

### D5 — Job tracking lives in `QueuedPhotoDownloadJobs`

The download session's imports are launched per staged resource from an adapter callback
(`SnapSyncApp.kt:328`), so the receipt has nothing to await. `QueuedPhotoDownloadJobs` already holds that
receipt (`backgroundCompletion`) and already owns a scope, so `onStaged` becomes `suspend`, the feature
owns the launch and tracks it, and `onBackgroundEventsFinished` joins the outstanding set before
releasing. `compose/` loses its fire-and-forget entirely, which is what makes "no launch in a compose
adapter callback" a checkable rule.

Rejected: a shared tracker in `compose/` (leaves the wrong shape writable); making `onStaged` block the
URLSession delegate queue (trades one hazard for another).

### D6 — Two bounds, work-level primary

**Forcing proof — a timeout on the import await is safe.** Invocation #5 issued `performChanges` at
~09:03:37 and never received its completion, yet the main thread kept running: `← onSilentPush (38ms)` at
09:03:37, `→ reconcile` at 09:03:38, a further burst at 09:06:27 — all on `Dispatchers.Main`. If
`performChanges` blocked its caller, none of that could have been logged. So the coroutine merely
suspends; cancelling it abandons a continuation, not a thread. Apple documents the method as
asynchronous, with `performChangesAndWait` as the synchronous sibling. Expires if a future SDK makes the
async form block.

This corrects an over-application of `IosDiscovery.kt`'s proof, which concerns
`fetchPersistentChangesSinceToken` — a genuinely blocking synchronous fetch — and does not transfer.

The work-level timeout fixes something the receipt-level bound cannot: cancelling unwinds `withLock` and
**releases `DownloadController.mutex`**, which invocation #5 held from 09:03:37 until process death and
would otherwise have held forever, blocking every later reconcile, import and leave in that process.

The receipt-level deadline covers the different failure of many healthy units summing past the budget.
Neither substitutes for the other. On expiry the receipt is released and the work continues detached —
today's behaviour for that one case, never worse.

### D7 — An import deadline stops the drain for that wake

Alternatives: continue to the next asset; continue with a per-wake cap.

The one observed hang was **environmental, not asset-specific**: `7CD3AF64` hung inside `performChanges`
for three minutes and the same asset, from the same staged bytes, imported in under a second at 09:06:47
in the next process. Continuing would generate several abandoned-but-possibly-committed transactions per
wake, each a duplicate candidate in the hazard handed to `fix-duplicate-import-on-restart`. Stopping caps
the exposure at one per wake.

### D8 — The HTTP client gets an explicit request timeout

`darwinHttpClient` installs no `HttpTimeout` plugin — the field exception reads
`socket_timeout=unknown` precisely because the timeout came from NSURLSession, not ktor. Nothing
legitimate exceeds 1.7 s: every fetch that answered did so between 150 ms and 1673 ms. The five failures
(64 s, 169 s, 419 s, 1191 s, 1642 s) are suspension artifacts — a default `NSURLSession` runs in-process,
so a frozen app services nothing, its wall-clock idle timer expires, and the task reports on the next
wake. A short ceiling turns those into fast honest failures and bounds the network portion of a
receipt-held span; `reconcile` already keeps last-good state on a union failure, so a fast failure costs
a retry, not correctness.

Corollary worth keeping: with a short ceiling in place, a fetch still reported as minutes long **is** the
suspension signal, for free.

### D9 — Deadline sources and values

Receipt deadlines are per-entry-point constants, plus the OS's own signal where one exists —
`BGTaskScheduler`'s `expirationHandler`, which `app.snapsync.download.backstop` does not register today.
`UIApplication.backgroundTimeRemaining` is deliberately not used: it may only be meaningful while holding
a task assertion, and D1 scopes assertions out; depending on an unverified API that silently reports
`greatestFiniteMagnitude` would mean the bound never fires.

The download backstop is a `BGProcessingTask` and can legitimately get minutes, so a single global
constant is wrong; its expiration handler is the authority with a generous fallback.

### D10 — Guards

The type is primary. Two `:test:architecture` zone gates back it, in the same textual shape as the
existing `flow-no-ports` gate: `flow/` declares no `CoroutineScope`, and no flow constructor takes a
non-suspend effect lambda. Both are absences, which is what makes them cheap and total.

### D11 — The timeout aftermath is handed over, not solved

An import abandoned at its deadline may still commit in `photolibraryd`, leaving a library asset with a
non-`IMPORTED` row. `recordCreatedLocalId` already writes the handle inside the change block, so the
evidence exists. Solving it here would be `fix-duplicate-import-on-restart`'s fix arriving in this change.

### D12 — No bespoke suspension detector

A wall-clock-versus-monotonic delta per entry point was specified, then dropped during implementation.

**Forcing proof — the delta is always zero.** Kotlin/Native's monotonic clock advances at the wall-clock
rate through whole-process suspension. Measured on two cleanly-paired invocations in the same dump:
`onResourceStaged` reported 89,990 ms of monotonic against a 90,000 ms wall gap (−10 ms) across the 89 s
freeze, and `platform.discoverResources` reported 6,241,150 ms against ~6,242,000 ms (~−850 ms) across
the 104-minute one. A detector built on that difference would read zero in exactly the cases it exists
for. Expires if a future Kotlin/Native switches its monotonic source to one that stops during suspension.

The axis was wrong in principle too: a blocked thread and a frozen process advance *both* clocks
identically. What separates them is whether anything else in the process ran during the gap, which needs
a heartbeat (a coroutine ticking while a receipt is held) or nothing at all.

Nothing was chosen. After this change two free signals cover the question: with an explicit HTTP ceiling
(D8), any fetch still reporting minutes is unambiguously a suspension artifact; and the deadline-fired
lines say when a bound fired rather than the work finishing. Millisecond timestamps (`diagnostic-logging`)
supply the ordering that this investigation had to deduce. A heartbeat stays available if those prove
insufficient in the field.

## Risks / Trade-offs

- **Late receipts may cost wake budget** — iOS penalises lateness, not only non-answer, and the shell's
  own comment notes an unanswered `content-available` push costs future wakes. → Typical held span is
  ~1.5 s against ~30 s; the HTTP ceiling and the per-entry-point deadlines bound the tail; the
  deadline-fired log lines are the detector. Only observable over days on a real device.
- **Honest durations read as a regression** — `← onSilentPush (18ms)`, `← onForeground (82ms)` and
  `← runDownloadBackstop (48ms)` are lies today and will become seconds. → Called out here and in the
  proposal so the next dump is not misread.
- **Constants are set on estimates** — three estimates in this investigation were an order of magnitude
  wrong and the log had the real number each time (HTTP 15 s → 0.4 s; detached fetches survive → 5-in-9
  die; discovery walk 10 s → 0.3 s). → The observability ships in the same change; read the first dump
  and tune once.
- **Circuit-breaking delays a batch if a hang really is asset-specific** → The evidence says otherwise,
  and the cost is one wake's delay against duplicate photos, which are user-visible and unfixable.
- **A timed-out import may still commit** → D11; logged, handed over.
- **`model/` gains a concept shaped by an OS lifecycle** → It is a pure value type over a lambda plus a
  suspend hold; the platform knowledge stays in the shell, which constructs it. Precedent:
  `Logger.invocation`.

## Migration Plan

Single change, one build. No persisted-data migration: nothing about the durable schemas changes, and the
receipt is process-local. Rollback is a revert — the change removes no state and writes no new state.

Verification is a full device matrix, because background wakes are OS-scheduled and mostly cannot be
forced: a silent push via `POST /events/<id>/notify` (reachable headlessly from Linux) for the push
receipt and the flow-suspend change end to end; `BGTask` simulation over the ssh-mac loop; and a
killed-app background download to reach `handleEventsForBackgroundURLSession`. The oracle in each case is
`debug.log` — the held duration and any deadline-fired line.

## Open Questions

- The receipt deadline constants. The ~30 s push and URLSession budgets are commonly cited rather than
  documented exactly; the deadline-fired lines will measure them in the field.
- Whether a late PhotoKit completion resuming a cancelled continuation is a clean no-op on
  Kotlin/Native. Expected, but it wants a test rather than an assumption.
- Whether abandoned transactions actually commit. The change-block tracing answers it after the fact,
  and the answer belongs to `fix-duplicate-import-on-restart`.
- Whether holding ~1.5 s per wake changes how often iOS wakes the app.
