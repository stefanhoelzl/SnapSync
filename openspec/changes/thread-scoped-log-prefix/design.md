## Context

Every device-log line carries a `[<entryPoint>]` prefix read from `LogContext.current`, a
process-global slot in `:adapter:ios:ext-safe`. `Logger.invocation` enters it through the `LogScope`
port (bound on iOS to `IosLogScope`); the first entry wins and holds it until it exits. All three
writers — `FileLogWriter`, `PublicNSLogWriter`, `SentryLogWriter` — read that one accessor.

The global form was chosen deliberately (`changes/archive/2026-07-06-diagnostic-logging`, D2): the
writer is a plain synchronous call with no coroutine context, and a trigger's work hops threads (Ktor
on the Darwin queue, SQLDelight), so a per-thread slot would drop the prefix exactly where it is wanted.
That record justified the global's inaccuracy by serial entry-point delivery.

**What was measured** (build 816, SE2, 2026-09-14 09:16:43, `debug.log`):

```
43.402  [Info/SnapSyncRoot] ← arm.onPermissionChanged (278ms)             launch work, unprefixed
43.560  [didReceiveMetricPayloads] → didReceiveMetricPayloads(count=2)
43.640  [didReceiveMetricPayloads] [gallery] gallery: fetched 1753 candidate(s)
43.758  [didReceiveMetricPayloads] [Http] GET …/api/v2/events/… → 200
43.792  [didReceiveMetricPayloads] [Http] PUT …/api/v2/devices/… → 201
43.793  [didReceiveMetricPayloads] [PushRegistration] push token registered
43.830  [didReceiveMetricPayloads] [SnapSyncRoot] upload ledger agrees with the backend
43.850  [didReceiveMetricPayloads] ← didReceiveMetricPayloads (292ms)
```

The lines in between are launch-time work that holds no entry point. They are not nested in the
delivery; they merely ran while it held the slot. MetricKit hands payloads over about a second after
launch, so this overlap is the normal case for that callback, not a race.

## Goals / Non-Goals

**Goals:**

- A MetricKit delivery's prefix appears on its own lines and on no other thread's lines.
- Work that switches threads keeps exactly the attribution it has today.
- The spec's accepted inaccuracy matches what was measured.

**Non-Goals:**

- Attributing launch-time work to a trigger. It has no entry point, and remains unprefixed.
- Fixing overlap between two process-global entry points (a silent push during launch work still
  labels that work). Closing it needs every piece of background work to hold its own entry point.
- Moving any entry point other than the two MetricKit callbacks.
- Any change to `:domain`, the `LogScope` port, or the `invocation` helper.

## Decisions

### D1 — A thread-scoped claim beside the process-global one

`LogContext` gains a second slot, local to the calling thread. `LogContext.current` resolves the
thread-scoped claim first and falls back to the process-global one. A second `LogScope` binding,
`IosThreadLogScope`, enters the thread-scoped slot. `IosLogScope` is unchanged in what it claims.

**Why this is exact for a synchronous callback:** while a synchronous call runs, it occupies its
thread, so every line logged on that thread during the call belongs to it and no line on another
thread does. The 2026-07-06 objection to per-thread slots — work that hops threads loses the prefix —
does not arise for work that does not hop.

**Why the writers need no change:** they already read the single accessor. Resolution lives in
`LogContext`, so the three writers and their tests stay as they are.

**Alternatives considered:**

- *Record every active entry point and print them all* (`[a|b]`). Rejected: it does not fix the
  measured case — the mislabelled lines belonged to no entry point, so the set would contain only
  `didReceiveMetricPayloads`. It also prints two names after an ordinary hop, because a suspended
  invocation resumes on a pool thread that looks like a new one.
- *Let the MetricKit callbacks log without claiming the slot.* Rejected: their own lines, and any
  instrumented seam they reach, lose the prefix the spec requires every line to carry.
- *Give launch work its own entry point.* Rejected as a fix: with one slot, whichever entry point
  enters first still labels the other's lines.
- *Carry the trigger in the coroutine context.* Rejected again: the writer still has no handle to it,
  and `kotlinx-coroutines-core` 1.10.2 for iOS declares no public `ThreadContextElement` (read from
  its klib), so there is nothing to bridge it onto a thread either.
- *Make the whole slot per-thread.* Rejected for the 2026-07-06 reason, which still holds.

### D2 — Outermost wins across both kinds of claim

On a thread holding a thread-scoped claim, **any** enter is nested and claims nothing, including an
`IosLogScope` enter by a seam the callback reaches. Without this, an instrumented seam called from
inside a delivery would take the process-global slot and bring the bleed straight back.

A thread-scoped enter succeeds on its own thread while a process-global claim is held elsewhere: the
callback is a separate trigger that occupies this thread, and its lines are its own.

Exit clears only a slot the caller established, as today, so a throw still restores both through
`invocation`'s `finally`.

### D3 — The holder is a `@ThreadLocal` object

The thread-scoped slot is an object annotated `kotlin.native.concurrent.ThreadLocal`, which the Kotlin
2.4 native stdlib declares without the obsolete-workers opt-in. It sits beside `LogContext` in
`:adapter:ios:ext-safe`, the module the law "State and authority" already grants the process-global
holder.

**Alternative considered:** a map keyed by `pthread_self()` behind a lock. Rejected: more code, a lock
on every log line, and it can leak an entry for a thread that dies while claimed.

### D4 — Which entry points may claim per thread

An entry point may take the thread-scoped claim only when its body does not suspend and launches no
work whose lines should inherit its prefix. Asynchronous work it started would run on other threads
and log **unprefixed** — the safe failure, since an unprefixed line is not misattributed.

Both MetricKit callbacks qualify: `ProcessMetricHandler` flattens, attaches the context and logs, all
before the callback returns, which `crash-reporting` already requires so a one-shot report cannot be
lost. This change moves only them. Other synchronous callbacks can follow one at a time, each checked
against this rule in its own change.

### D5 — How it is proven

- **Writer-level tests** in `:adapter:ios:ext-safe`'s `iosTest`, beside `FileLogWriterTest`:
  a thread-scoped claim prefixes a line on its own thread; a line logged from another thread during
  the claim carries no prefix; a process-global claim still reaches another thread; a process-global
  enter nested under a thread-scoped claim takes nothing. The second thread comes from a
  private `NSOperationQueue`, which needs no obsolete API — and not from GCD, because the
  extension-safety gate scans this test source set too and does not permit `platform.darwin`.
  These run under `iosSimulatorArm64Test` (on CI, or on the ssh Mac), not on Linux, where only the
  metadata compile is available.
- **On device:** the next MetricKit delivery that overlaps launch work shows launch lines unprefixed
  and the delivery's own lines prefixed. Deliveries arrive about daily, so this waits for one.

## Risks / Trade-offs

- [A future edit makes a MetricKit callback launch async work] → its lines log unprefixed rather than
  misattributed; D4's rule is stated in the callbacks' KDoc so the edit meets it.
- [A thread-scoped claim is left set on a pooled thread] → `invocation` exits in `finally`, and D2
  refuses nested claims, so a claim lives exactly as long as the outermost call on that thread.
- [Process-global entry points still mislabel concurrent un-entered work] → stated in the spec as the
  accepted inaccuracy, with the measurement, instead of justified by serial delivery.
- [Evidence is one device, one delivery] → the mechanism is exact by construction and pinned by the
  writer tests; the device check confirms the wiring, not the argument.
