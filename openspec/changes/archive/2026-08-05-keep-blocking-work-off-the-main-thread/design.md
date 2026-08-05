## Context

`SnapSyncRoot` owns a process-lifetime `CoroutineScope` on `Dispatchers.Main`. Everything the app does
outside the UI runs there: OS-callback flows, feature coordination, and — through the ports — every
blocking platform call. A blocking call on that scope is a main-thread block, and a main-thread block
past roughly ten seconds is an OS kill.

Whether any given call blocks *the main thread* is therefore not decidable from the call site. The same
adapter is safe in the extension process (whose cycle runs under `runBlocking` on the OS-invoked thread)
and lethal in the app. The current law asks each sync-I/O port impl to hop off main; measured compliance
is 2 of 23, and both compliant seams were written after an incident rather than before one.

Two facts from the rebase shape this design. `hold-os-receipts-until-work-completes` made trigger flows
`suspend` and forbade them from holding a `CoroutineScope`, so OS-callback work now reliably enters
through `scope.launch` — the scope's dispatcher governs it. And that change's own guard records the
measurement that motivates honest instrumentation: `← onSilentPush (18ms)` against 41 s of real work.

## Goals / Non-Goals

**Goals:**

- A blocking platform call is off the main thread because of where it is composed, not because its author
  remembered a rule.
- The rule that replaces it is mechanically checkable, and the check is cheap and exact.
- Concurrency semantics are unchanged, so the change is one property wide.
- The reasoning survives: the forcing proofs currently embedded in adapter comments stay true and stay
  attached to the decision they justify.

**Non-Goals:**

- Proving that nothing blocks the main thread. That is not decidable; the main lane is made unreachable
  by default and reachable only by allowlist.
- Fixing throughput. A wedged `assetsd` still parks the cycle that touched it; it parks off-main.
- Removing the two existing dispatcher hops. Their justification changes; their presence does not.
- The detection half (pinning and proving Sentry's app-hang options) — declined by the operator; see
  Decision 10.

## Decisions

### D1 — Two shared lanes plus a dedicated scope thread

```
Dispatchers.Main                        UIKit only — share sheet, photo picker,
                                        Settings link, permission prompt
Dispatchers.Default                     CPU work — Orbit's state reduction
                                        (already there; unchanged by this change)
newFixedThreadPoolContext(1, "…")       the live core's composition scope — one
                                        dedicated thread for blocking platform
                                        calls, network, and durable stores
```

**`Dispatchers.IO` is not available.** It is `internal` on Kotlin/Native in coroutines 1.10.2 — present
in the artifact, absent from the public API. Measured, not assumed: a probe compiled against `iosMain`
fails with *"Cannot access 'val IO: CoroutineDispatcher': it is internal in 'kotlinx.coroutines.Dispatchers'"*.
The klib symbol table lists `IO`, `<get-IO>` and `DefaultIoScheduler`, which is what a symbol table does —
it records what ships, not what is callable. The three comments in this repo saying Native has no
`Dispatchers.IO` are therefore substantially right and are **sharpened, not corrected**: what is missing
is a *public* one. Expiry trigger: a coroutines release that publishes it.

That leaves the reason blocking work should not sit on `Dispatchers.Default` intact — Orbit's event loop
runs there, so a wedged `assetsd` would stall state reduction, trading an OS kill for a frozen UI — and
the only remaining way to honour it is a pool of our own. One thread is exactly what the scope needs
(see D2), so the scope takes a dedicated single-thread context rather than a slice of a shared pool.

`newFixedThreadPoolContext` carries `@DelicateCoroutinesApi` because such a context is never closed. Here
that is the intent, not a leak: the scope is process-lifetime, and closing its dispatcher is precisely
what must not happen.

### D2 — The composition scope is serial

`Dispatchers.Main` is single-threaded. Code in the scope can rely on that for mutual exclusion, and does:

- `PhotoSelectionSnapshotSource` — *"Touched only on [scope] (serial main), so the register/unregister
  dance needs no lock."*
- `SentryDiagnosticsReporter` — *"Composition runs on the main thread, so a plain flag suffices."*

Those are the two that wrote it down. The ones that did not cannot be enumerated, so a serial→parallel
switch across the whole app graph would be a broad, unverifiable behavioural change whose failure mode is
a race. A single-threaded context moves the thread and leaves seriality intact.

A dedicated thread gives **thread confinement**, not merely mutual exclusion — strictly more than this
design needs, and free. That margin matters precisely because the assumptions cannot be enumerated: any
that happen to rely on same-thread execution rather than on non-overlap survive too. (Nothing found so far
needs it: `LogContext`, the ambient `[entryPoint]` prefix, is deliberately a process-global rather than a
`@ThreadLocal` so it survives dispatcher hops. Its docstring accepts one trade-off — "two
genuinely-overlapping invocations can mislabel a line…iOS delivers app entry points serially per process"
— which a serial lane preserves and a parallel one would have quietly broken.)

*Alternatives rejected:* `Dispatchers.Default.limitedParallelism(1)` — no delicate API and no extra
thread, but a blocked call holds a worker in the pool Orbit's reduction runs on, which is the outcome D1
exists to avoid; and an unrestricted pool, which is more concurrency than the code has been audited for
in exchange for throughput this app does not need.

### D3 — Two doors, because the scope governs only one of them

Orbit's `RealSettings` defaults `intentLaunchingDispatcher` to `Dispatchers.Unconfined` (and
`eventLoopDispatcher` to `Default`). `Unconfined` starts a coroutine inline on the calling thread and
overrides the scope's dispatcher, so an `intent { }` fired from a Compose tap begins on the main thread
whatever the scope says. A `suspend` function that never actually suspends — `IosAlbumManager`'s
synchronous PhotoKit XPC is exactly this shape — then runs to completion there.

So the scope closes the OS-callback door and nothing else. User taps need their own.

### D4 — The tap door is closed at the existing decoration point, split in two

The commands are already built and decorated in one place (`compose/`), which the "Commands cross one
door" law names. The lane goes there.

It cannot be one uniform decorator: `share`, `requestAccess`, `openSettings` and `choosePhotos` present
system UI and must stay on main (`openURL` off-main is a main-thread-checker violation). Two decorators
with **no default** make the lane an explicit choice at the point a command is written, and a forgotten
choice fails to compile.

*Alternative rejected:* overriding Orbit's `intentLaunchingDispatcher`. One line, but it changes a
library's own semantics — Unconfined launching is what makes an intent's first segment run in call order —
trading a threading bug for a possible ordering bug in code we do not own.

### D5 — `create` and `rename` move their launch into `compose/`

Both features hold a `CoroutineScope` and launch into it, so the tap command returns immediately and the
`Logger.invocation` wrapper measures the hand-off rather than the work: `← tap.create (1ms)` against a
multi-second mint. That is the same false-duration defect `hold-os-receipts-until-work-completes` removed
from the OS-callback side, in the tap trail instead.

Making the features plain `suspend` and moving the launch to the decoration site fixes it without changing
what the user sees: the tap still returns immediately, the outcome still rides `creationStatus` /
`renameStatus`, the log now spans the real work, and the work rides the scope's lane rather than needing
its own. It also removes two of the six `feature/` classes that hold a scope, leaving the three long-lived
machinery classes and `LeaveEvent`'s single documented exception.

*Alternatives rejected:* making the commands await the work (undoes the latch design — the screen would
block on the network); logging inside the feature's own launch (honest, but moves tap decoration out of
`compose/`, and leaves the work on whatever lane the feature chose).

### D6 — The two existing hops stay where they are, and mean something different

`IosDiscovery` and `PhotoKitCandidateSource` hop to `Dispatchers.Default`. With no public `Dispatchers.IO`
(D1) there is nowhere better to send them without inventing a second pool, so the target does not change.

Their **justification** does. They are no longer "keep this off main" — the composition scope does that
now, for every adapter, whether or not it hops. They are "let this work proceed concurrently with other
app-scope work", i.e. a throughput opt-in against a scope that is deliberately serial. The rewritten
comments say that, and keep the build-521 forcing proof attached to the decision it still supports.

Residue, recorded rather than fixed: those two hops put blocking PhotoKit XPC on `Dispatchers.Default`,
the pool Orbit's reduction uses. That is pre-existing behaviour, bounded (two seams, a handful of
concurrent calls), and strictly better than the main thread. Giving them a second dedicated pool is more
invention than this change warrants; if the residue ever bites, the fix is a named platform-I/O context
alongside the scope's.

### D7 — Constructor I/O is fenced, not fixed

`FileBackedConfigStore` reads the App-Group file and falls back to a Keychain read in its constructor. The
graph assembles on whichever thread touches `host` first — a launch (off-main) or `MainViewController`
(main) — so the hazard is a race, which is why it has never been observed.

Fixing it collides with a deliberate rule: the container's first state is built from seams that "hold
their current truth synchronously…never a guess or a placeholder", which requires the I/O to have happened
before construction. Removing the constructor read means either a not-ready first frame or a
launch/render reordering across the Swift boundary — a redesign on the launch path, with no test that can
prove it.

So: a detekt rule forbids blocking calls in property initialisers and `init` blocks under
`adapter/ios/**`, with this one instance grandfathered by name and reason. The class cannot grow; the
instance waits for the sentinel (D9) to force the conversation with evidence.

### D8 — The world harness composes on the production lane structure

`FullStackHarness` composes the live core with `rememberCoroutineScope()` — the AWT event thread. A law
saying "the live core's scope is never UI-bound" would be false on the day it lands, and gate 1 would not
catch it (it watches `Main` symbols; `rememberCoroutineScope` is not one).

The harness changes rather than the law gaining an exception. It restores the harness's own claim — the
same `snapSyncApp` the device shell calls — to cover threading and not only the graph, and it makes the
harness the place where "state produced off the UI thread" is exercised headlessly.

### D9 — Two gates now, the sentinel later

Gate 1 contains the main lane (`Dispatchers.Main`, `MainScope()`, `dispatch_get_main_queue`,
`NSOperationQueue.mainQueue`, `DispatchQueue.main`) to an allowlist of UIKit adapters — a lexical
containment gate in `KeychainContainmentTest`'s shape. Gate 2 requires every field of the command bundle
to be built through one of the two decorators.

Deliberately deferred: a runtime sentinel in the world harness that fails a test when a port is touched
from the UI thread. It is the only mechanism that would catch what text cannot — constructor I/O, a
forgotten lane, an unanticipated shape — but it is test infrastructure of a different kind and would
roughly double this change. Recorded here so its absence is a decision.

### D10 — The detection half is out of scope by operator decision

Sentry's `ANRTracking` integration is enabled by inherited default, and no app-hang event has ever reached
Bugsink, so the path is unverified. Pinning `enableAppHangTracking` / `appHangTimeoutInterval` explicitly,
and proving the path once with a DSN-injected dev build, were both proposed and declined. Consequence,
recorded rather than argued: with the sentinel deferred too, the gates are the only protection and they
are static — a lane that leaks through a path no text gate sees surfaces nowhere.

## Risks / Trade-offs

- **A blocking call parks all other app-scope work** (serial lane) → Already true today, where the same
  work is serial on main. The change removes the kill, not the queueing. The UI is on main and Orbit's
  reduction on Default, so neither is affected.
- **Compose Desktop proves stricter than Compose iOS about off-thread state production** → The world
  harness fails on Linux before any device is involved. A false alarm to diagnose, not a shipped defect —
  and cheaper to find there than on a phone.
- **The UI lane is not covered by any test** → Its four commands are fakes on desktop, and exercising them
  on device needs taps, which need a signed WebDriverAgent this project lacks. Mitigated by keeping the
  surface small and reviewable (D4, gate 2) and by the failure being loud: the main-thread checker fires
  on Debug device builds on first use.
- **The dedicated thread is never released** → Intended. The scope is process-lifetime; the
  `@DelicateCoroutinesApi` warning is about contexts that outlive their use, which is the requirement here
  rather than the hazard.
- **A platform-capability claim was taken from a klib symbol table rather than a compile** → Already
  fired: `Dispatchers.IO` looked available and is `internal`, which invalidated the first draft of D1, D6
  and part of the spec delta. The compile-first task stays first for that reason, and the lesson is
  recorded here rather than in a law: a symbol table records what ships, not what is callable.
- **The two documented seriality assumptions may not be the only ones** → D2 exists precisely because they
  cannot be enumerated; seriality is preserved rather than audited.

## Open Questions

None blocking. `DefaultIoScheduler`'s exact parallelism cap on Native is unpinned and does not matter at
this concurrency: the scope uses one thread of it and only two seams opt into the full pool.
