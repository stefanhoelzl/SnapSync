## Context

### What was reported, and what the device log says

Bugsink `SNAPSYNC-26` is an operator-triggered diagnostic dump (not a crash): *"settings and title edit buttons
appeared only after a while"*. Build 0.4 (607), iPhone11,2, iOS 18.7.9, `url_session` tier, `process=app.snapsync`,
`ext_log` absent (iOS 18 has no extension). The app log reconstructs the moment exactly:

| time (UTC, 2026-08-21) | line |
|---|---|
| 19:05:16.4 | `tap.leave` — left the previous event `85afee89…` |
| 19:05:47.8 | `=== app process start build=0.4(607) ===`, launched from the invite universal link |
| 19:06:08.6 | `tap.commitJoin(08d861e8…)` |
| 19:06:11.6 | enroll `PUT …/devices` → 201; **`→ provisionEvent`** |
| 19:06:14.9 | `← provisionEvent (3265ms)`; `← tap.commitJoin = joined=true (6299ms)` |
| 19:06:52.6 | `tap.sendDiagnostics(screen=Joined)` — this report |

### The mechanism

Two facts are written by two different owners, at two different times:

- `config` — the persisted `EventConfig?`, written by `flow/Provision.run()` as **step 2 of 6** (`saveConfig(cfg)`),
  i.e. at 19:06:11.65.
- `pending` — the in-memory `PendingJoin?`, cleared by `StatusContainerHost.commit()` only **after**
  `commands.commitJoin(...)` returns, i.e. at 19:06:14.91.

Between them sits the rest of the provision: `refreshStatus()`, `uploadArm.onProvision()` (a full URLSession cycle
including a device-manifest `PUT`), `ensureAlbum()`, and a concurrent reconcile `GET …/files` + push `PUT …/devices`.

The reduction then computes:

```kotlin
// A pending join for a DIFFERENT event while joined is a switch confirmation over the joined screen.
val pendingSwitch = pending?.let { PendingSwitch(it.eventId, it.phase) }
```

The comment says DIFFERENT. **The code never compares `it.eventId` to `config.eventId`.** `onOpenUrl` filters the
same-event case at the entry (`current.eventId != eventId → startPending`, else no-op), so the only way to reach a
same-event pending join is the commit itself — and the commit reaches it on every join.

| | `config` | `pending` | reduced state | gear + pen |
|---|---|---|---|---|
| before the Join tap | `null` | `E, Ready` | `JoiningEvent(E, Ready)` | n/a |
| **11.65 → 14.91** | `EventConfig(E)` | `E, Committing` | `Joined(…, pendingSwitch = PendingSwitch(E, Committing))` | **hidden** |
| after 14.91 | `EventConfig(E)` | `null` | `Joined(…, pendingSwitch = null)` | shown |

`SwitchDialog` renders **nothing** for `Committing` ("Transient — no dialog while the details load or a commit runs"),
so `pendingSwitch != null` has exactly two visible consequences in the whole UI and no others: the gear vanishes from
the action row and the pen vanishes from beside the heading. Everything else — the heading, the status line, share,
leave — renders normally, because nothing else reads `pendingSwitch`. That is precisely the reported symptom.

### Why the suppression exists, and why it does not earn its keep

The stated rationale, in the spec and in both call-site comments, is that a reconfigure or a rename "must not race a
switch's config write". Checked against the code, that race is already prevented three times:

1. `ReconfigureEvent.reconfigure(eventId, …)` — *"if the current config is absent or names a **different** event (a
   switch landed while the surface was open), this is a **no-op**"*.
2. `RenameEvent` — *"read the current config, **guard the `eventId` still matches**"*.
3. `StatusScreen`'s `LaunchedEffect(joined) { if (!joined) { reconfiguring = false; renaming = false } }` — a completed
   switch clears the config, which closes both surfaces on its own.

It is also inconsistent: **leave** ends the membership the switch is about to end, and **share** hands out an invite
URL derived from a config about to be replaced. Neither is suppressed, and the rationale does not distinguish them.

And its real coverage is nearly empty. `SwitchDialog` uses `BasicAlertDialog` — modal:

| switch phase | dialog | screen live | suppression does anything |
|---|---|---|---|
| `Loading` | none | yes | yes — the only genuine window (the `GET /events/:id`, ~400 ms in this log) |
| `Ready` / `NotFound` / `LoadFailed` | modal | no | no — nothing is tappable, share and leave included |
| `Committing` | none | yes | documented unreachable for a switch; only this defect reaches it |

### The two defects the investigation exposed

**Non-terminal resting phases.** `JoiningEventScreen`'s action cluster ends with
`JoinPhase.Loading, is JoinPhase.Committing -> Unit` — *"In-flight phases offer no actions."* So `Loading` and
`Committing` pin no Cancel. If an intent throws while one is set, the surface is a dead-end spinner for the process
lifetime:

| stranded phase | config | renders | escape |
|---|---|---|---|
| `Loading` | absent | full-screen "Loading event details…" | **none — force-quit** |
| `Loading` | present | nothing (an invisible pending join) | n/a |
| `Committing` | absent | full-screen committing spinner | **none — force-quit** |
| `Committing` | present | the joined screen | n/a once the suppression is gone |

Every other phase (`ExplainAccess`, `Ready`, `NotFound`, `LoadFailed`, `CommitFailed`) pins an action, so the policy
"never rest in a non-terminal phase" reduces to exactly two write sites: `commit()` and `loadInto()`.

**A throwing intent disables the container.** Measured against the shipped `orbit-core-jvm-10.0.0` artifact, not
inferred: `RealContainer$initialiseIfNeeded$2$1` is

```kotlin
runCatching { intent(pluginContext) }.exceptionOrNull()?.let { t ->
    settings.exceptionHandler?.handleException(coroutineContext, t) ?: throw t
}
```

`RealSettings`' defaults are `eventLoopDispatcher = Dispatchers.Default`, `intentLaunchingDispatcher =
Dispatchers.Unconfined`, and **`exceptionHandler = null`**; `SnapSyncRoot` passes no settings. The container's
`intentJob` is `Job(parent)` — a plain, **non-supervisor** job — and each `orbit()` call creates another plain
`Job(parent = intentJob)`. So the rethrow cancels `intentJob`, and every later intent is a child of a cancelled job.

Two probes on a real `CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)` (a first probe under `runTest`
was discarded — `TestScope` distorts the handler result):

| | today (no `exceptionHandler`) | with `exceptionHandler` set |
|---|---|---|
| subsequent intents run | **no** — state stuck at 1 across two further intents | **yes** — state reached 3 |
| throwable observed | the host scope's `CoroutineExceptionHandler` | Orbit's handler |
| app crashes | no (`SupervisorJob` isolates) | no |

In the field that means: after one throwing tap the status screen keeps rendering its last state and **silently drops
every tap** — leave, share, settings save, rename, join confirm, cancel, create. It reports itself (the throwable
reaches `SnapSyncRoot`'s handler → `log.e` → a Bugsink event) but it recovers only by force-quit.

## Goals / Non-Goals

**Goals:**

- The settings gear and the rename pen are present in every `Joined` state, so a join in flight never removes them.
- No failure can leave the join gate resting in a phase that offers the member no action.
- No throwing intent can stop the status container from processing later intents.
- Every failure above stays visible: an `Error`-severity line, so it reaches both the device log and Bugsink.

**Non-Goals:**

- Making the reduction's `pendingSwitch` id-aware. Once the suppression is gone no consumer misreads a same-event
  pending join, and the residue is informative rather than harmful (below).
- Shortening the provision itself, or moving `saveConfig` within `Provision`. The order there is deliberate and
  unrelated.
- Reporting a half-run provision to the member. A throw after `saveConfig` means the join landed but steps 3–6 did
  not; those all re-run on the next foreground. Surfacing that to the user is a separate question.
- Any change to `:domain`, the adapters, the backend, or persistence.

## Decisions

### D1 — Remove the suppression rather than narrowing it to real switches

The one-line alternative is `pending?.takeIf { it.eventId != config.eventId }`, which makes the reduction match its own
comment and leaves the suppression standing. Rejected: it fixes the symptom while preserving a rule that is redundant
with three existing defences, inconsistent with the two neighbouring actions, and effective only during a ~400 ms
details fetch. Removing it is a smaller surface (`:ui:screens` only — no `:domain`, no `:ui:presentation`) and settles
the inconsistency instead of entrenching it.

A third alternative — keep the rule and extend it to leave and share — was rejected because it makes the joined layer
briefly actionless to protect against a race that cannot occur.

**Consequence accepted:** during a genuine switch's `Loading` phase a member can now open the settings surface or the
rename dialog, and the "Switch events?" dialog then appears over it. Confirming the switch clears the config, which
closes the surface via the existing `LaunchedEffect(joined)`; cancelling leaves the surface open for the still-current
event, which is correct. Confirming a stale Save or rename is a no-op by the eventId guards.

### D2 — Leave the reduction emitting a same-event `pendingSwitch`

After D1, `pendingSwitch` has two consumers: `SwitchDialog`, which renders nothing for the only same-id phase reachable
(`Committing`), and `screenLabel`, which names the surface in a diagnostic dump. The dump may therefore read
`Switch:Committing` for a plain join. That is kept deliberately: a dump saying `Switch:Committing` **with a config
present** tells a triager that a commit was in flight or stranded, which is exactly the diagnostic worth having.

Cost, stated plainly: `Switch:` in a dump no longer implies an actual switch, and the defect is not representable at
the `UiState` layer — so its regression guard can only live in `:ui:screens`, as a Compose test that the two
affordances render for a `Joined` carrying a `pendingSwitch`.

### D3 — On a throw, `commit()` decides from the config

A bare `finally { pending.set(null) }` is wrong: it would clear the retry surface on the ordinary failed-enroll path
too. "Always `CommitFailed`" is wrong in the other direction: when the config *did* land, `Joined(pendingSwitch =
CommitFailed)` renders nothing either, so it strands at a different phase. "Always clear" silently drops a member onto
the create screen when the throw preceded the config write.

So the catch reads the config — the same fact `Provision` writes at step 2:

- `config.value?.eventId == p.eventId` → the join landed; drop the pending join. The joined screen is the truth.
- otherwise → the join never landed; `CommitFailed`, which pins a Retry.

The eventId comparison declined in D2 lives here instead, where it decides something.

`loadInto()` is simpler: a throw becomes `LoadFailed`, converging with what a client returning `JoinLoad.Failed`
already produces.

### D4 — `loadInto()`'s guard is defence-in-depth, and says so

`loadJoinDetails` cannot throw today: production binds it through `HttpEventDirectory.fetch`, which is
`runCatching { … }.getOrDefault(EventDetails.Failed)`, and `toJoinLoad` is pure. But it is an injected
`suspend (String) -> JoinLoad`, and presentation cannot know that. The guard is kept and documented as
unreachable-in-production, in the spirit of `BackgroundUploadPump`'s comparable wrapper.

Corrected during implementation: that precedent's *other* half does **not** transfer. `BackgroundUploadPump` notes
that a mutation removing its wrapper survives the suite, because its failure cannot be provoked through the class's
public surface. Here the seam is a **constructor parameter**, so a test injects a throwing loader directly and the
guard is covered like any other branch. The claim the code makes is therefore "unreachable in production", not
"untestable".

### D5 — Both catches follow the established cancellation shape

`catch (cancelled: CancellationException) { throw cancelled }` before `catch (t: Throwable)`, as in
`LedgerCountsPoller`. A bare `catch (t: Throwable)` would swallow cancellation and repair state during teardown.

### D6 — Container liveness via Orbit's `exceptionHandler`, not by swallowing

Three options were weighed against the measurement in Context:

| | container survives | throwable reaches Bugsink |
|---|---|---|
| swallow in each intent | yes | no — the injected `log` seam is bound to `log.i` |
| rethrow (today) | **no** | yes |
| Orbit `exceptionHandler` | yes | yes, via whatever the handler is bound to |

Only the third gets both, and it is set once at the container's single construction site, so it covers **every**
intent rather than the two paths hardened in D3 — and it covers the device shell, both desktop harnesses, and every
test from one place. It is also what makes D3's rethrow safe: with the handler installed, Orbit consumes the
throwable instead of taking `intentJob` down with it.

### D7 — A dedicated `onIntentError` seam rather than widening the existing `log`

Because Orbit's handler consumes the throwable, it no longer reaches `SnapSyncRoot`'s `CoroutineExceptionHandler`, so
the handler itself must log at `Error` to keep the Bugsink event. `StatusContainerHost`'s existing
`log: (String) -> Unit` is bound to `log.i` and exists for the dev-path autoJoin abort lines; overloading it with
severity would change an existing parameter's shape at every construction site and put severity vocabulary into
`:ui:presentation`.

A new `onIntentError: (Throwable) -> Unit = {}` beside it is inert by default — so the harnesses and every existing
test construct unchanged — and the composition binds it to `log.e(t)`, which lands in `debug.log` and becomes a
Bugsink event under the `crash-reporting` capability.

### D8 — Capability split

`sync-status-screen` owns the reduction and the container, so container liveness is its requirement, alongside the two
affordance requirements losing their suppression clause. `join-event` owns the gate's phases, so the
"never rest in a phase with no action" requirement is its.

## Risks / Trade-offs

- **The Orbit behaviour was measured on the JVM artifact, not on the iOS klib.** → The klib is built from the same
  source, and the liveness regression test lives in `commonTest`, so CI runs it on `iosSimulatorArm64` and settles it
  there. If it ever diverges, that test fails rather than the behaviour drifting unnoticed.
- **Orbit's `exceptionHandler` is a library contract that could change on a version bump.** → The liveness test pins
  it: a bump that changes the semantics fails the build rather than silently restoring the bricking. Measured while
  implementing: the pin must **not** use `orbit-test`'s `test()` harness that every other test in the file uses, because
  the harness substitutes an exception handler of its own when the container carries none — so under it a later intent
  runs whether or not production configures one, and a harness-based version of this test passes on exactly the
  container it is meant to guard. The pin therefore drives the real container on a real supervised scope with real
  dispatchers and awaits real signals. All three upstream failure modes are then caught: Orbit no longer calling the
  handler, Orbit calling it but cancelling anyway, and Orbit reverting to the re-throw.
- **Removing the suppression allows a settings surface or rename dialog to be open when a switch confirmation
  appears.** → Confirming closes it via the existing `LaunchedEffect(joined)`; a stale Save or rename is a no-op by
  the two eventId guards. Visually busy for the duration of a details fetch; not corrupting.
- **A throw after `saveConfig` still leaves a half-run provision with no member-visible signal.** → Out of scope and
  stated as such: the join genuinely landed, the pending join is now cleared honestly, steps 3–6 re-run on the next
  foreground, and the throwable is reported at `Error`.
- **`screenLabel` can read `Switch:Committing` for a plain join.** → Accepted per D2; `Switch:` in a dump no longer
  implies a switch, and the change record is where that is written down.
- **The `loadInto()` guard is untestable through the class's public surface with the production binding in place.** →
  Accepted per D4, with the precedent named so a later reader does not mistake it for dead code.
