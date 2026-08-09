# Revert-proof results

Every behavioural claim in this change was re-broken in an **isolated `git worktree`** (never in place —
an in-place harness once died mid-mutation and left the defect staged on the branch) and the suite was
required to go red **naming a failing test**. A mutation that fails to compile, or that hangs, is not a
kill: the harness reports `COMPILE-ERROR` / `HUNG` separately and neither counts.

Harness: `git stash create` → detached worktree at that tree → one mutation at a time, `git checkout -- .`
between them, targeted Gradle task per mutation with `--rerun-tasks`.

| # | defect reintroduced | outcome | killed by |
|---|---|---|---|
| 1 | release at adopt instead of after the work | KILLED | `a handler is released after the work its drain feeds - not on the drain itself` |
| 2 | a second adopt orphans the first | KILLED | `a second handover does not orphan the first — both are released` |
| 3 | the deadline is removed | KILLED | `the deadline runs from the handover - not from the drain` |
| 4 | the guard's scan is pointed at nothing | KILLED | `the gate scans real files and its rule still recognises the shape` |
| 5 | a stored handler field returns to the shell | KILLED | `no production source outside the owning type stores an OS completion handler` |
| 6 | a coalesced caller returns immediately | KILLED | `coalescedBackgroundTaskStillResubmits` |
| 7 | a coalesced caller re-arms on `alwaysScheduleNext` alone | KILLED | `coalescedRelaunchTriggerRearmsOnlyOnRemainingWork` |
| 8 | the cancelled-drain cleanup loses `NonCancellable` | **SURVIVED** | — (see below) |

Seven killed, each by a **distinct** test — no single test carries more than its own defect. **One
survived, and it is reported rather than dropped.**

## Mutation 8 survived, and why that is the right answer

Independent review found that `drive()`'s `catch` also catches `CancellationException`, and that if
`Mutex.lock` had to suspend there it would throw before clearing `drainDone` — after which every trigger,
now *awaiting* that deferred instead of returning, blocks forever. The cleanup was wrapped in
`withContext(NonCancellable)`.

The mutation removing that wrapper leaves the suite green, and chasing it down changed the finding rather
than the fix: no critical section in the pump suspends while holding the lock, and every composition
injects a **serial** scope, so `lock()` always takes its uncontended fast path — which does not check
cancellation. The path is unreachable today. It becomes reachable if either half of that invariant goes: a
multi-threaded scope, or a `suspend` call added inside any `withLock`. Neither is compiler-enforced.

So the wrapper stays as defence in depth, and is documented at the site as *"does not fix a reachable bug
today, removes a way for one to appear"* — not as a demonstrated defect. A `SURVIVED` line is the accurate
record of that, and inventing a test that only appears to cover it would be worse than the gap.

An earlier run of this harness reported `MUTATION 2: BROKEN-PATCH` (its anchor had moved) and silently
omitted mutation 8. Neither counted: a mutation that does not apply proves nothing, which is why the
harness distinguishes `BROKEN-PATCH` / `COMPILE-ERROR` / `HUNG` from `SURVIVED`.

## Added at rebase: the expiry-line pin

Rebasing onto `main` picked up a requirement landed in parallel — *"The OS-receipt expiry line is
pinned"* — whose guard scanned one file, `OsReceipt.kt`. This change adds a **second** emitter of that
line in `BackgroundEventsReceipts`, and because `OsReceipt`'s deadline is `INFINITE` there by
construction, that second emitter is the *only* expiry evidence for both background-`URLSession`
handlers. A reword of it would have blinded every rig consumer for exactly the handlers this change is
about, with the guard still green.

The guard was generalised to pin the **set** of emitters, derived from source and compared in both
directions. Mutation: reword the second emitter's line.

| # | defect reintroduced | outcome | killed by |
|---|---|---|---|
| 9 | the second emitter's expiry line is reworded | KILLED | `every declared expiry emitter still emits the pinned line`, `the set of expiry emitters is exactly the declared one`, `each emitter emits the line exactly once` |

The "exactly once" check also fired unprompted during implementation, on a KDoc that quoted the pinned
literal while explaining it — the same prose-versus-code trade the handler-containment guard makes.

## What the harness does not prove

- **Mutations 1–3 and 6–7 run on JVM only.** The same tests also run on `iosSimulatorArm64` in CI, but the
  mutation runs did not; a Native-only divergence would be invisible here. (Two such divergences did show
  up during implementation — `kotlinx.coroutines.Runnable` and commas in backticked test names — both
  caught by `./gradlew build`, not by the JVM loop.)
- **Nothing here is a device measurement.** The field evidence in `design.md` came from three diagnostic
  dumps; this change's own behaviour on a device is unverified until a dump shows it.
- **The confinement guard's residue is untested by construction** — it catches storing, not releasing
  early, and mutation 5 only proves it catches the shape it claims to.
