## Context

A `REQUESTED` row means "a transfer for this key was created and has not reported an outcome". The engine
never re-issues a `REQUESTED` key, so a transfer that ends without reporting leaves its photo abandoned
unless something demotes the row. Two things do that today, and both are ledger-wide:

| where | today | assumption |
|---|---|---|
| `UploadCycle.reconcileStranded` (every app-driven cycle, since `2026-09-15-transport-only-seam`) | `REQUESTED − liveKeys()` → `markStranded` (`FAILED`) | every `REQUESTED` row is the app transport's |
| `OsDrivenUploadMechanism.stop()` (inside the re-register ritual, and on leave) | disable → `clearRequested()` (delete) → reset the discovery cursor | every `REQUESTED` row is PhotoKit's |

The hand-off from PhotoKit to the app-driven mechanism (`RelinquishThenRun`, decision record
`2026-08-25-collapse-upload-tier-seam` D5/D5b) runs neither PhotoKit repair: it deregisters only and relies on
the app-driven cycle's ledger-wide pass to recover PhotoKit's orphaned rows (`upload-lifecycle`, scenario "The
repair does not fire when the incoming mechanism reconciles precisely").

Constraints that shape everything below, each verified in the tree:

- The ledger has **no owner column**, deliberately, and PhotoKit's in-flight jobs cannot be enumerated from the
  app (`fetchJobsWithAction` offers only `.retry` and `.acknowledge`).
- `markTerminal` is guarded on `state = 'REQUESTED'` (`Ledger.sq`), so once a row is demoted a late success
  for it applies to nothing, and its bytes are uploaded again.
- `sync-ledger`: **exactly one process records**. On iOS ≥26.1 that is the extension; the app may only use
  the reset/bulk family there. `markTerminal` is the only record operation outside the writer, and only
  because the platform callback that calls it lives in the recording process.
- The app-driven adapter stages each resource to `upload-staging/<key>` in the App Group **before** creating
  its task, and deletes the file where the transfer ends (the delegate's terminal record, `cancelAll`, a
  cancel-by-key, a failed create). A force-quit or an OS-dropped transfer ends none of those ways, so its file
  remains. PhotoKit never stages.
- `UrlSessionUploadController.start()` runs `sweepStaging()` — deleting every staged file with no live task —
  before the first cycle of that start, destroying exactly that marker.
- Duplicate uploads are idempotent (deterministic keys, idempotent PUT) and, when rare, accepted.
- The app-driven mechanism is wrapped in `RelinquishThenRun` only where the OS carries the OS-driven one
  (`uploadMechanismTable`); below iOS 26.1 its `start()` is its own.
- Under a partial grant the relinquish is refused and the registration survives, inert: the OS does not
  invoke the extension (`ios-photokit-upload`), so nothing will ever settle PhotoKit's rows there either.

## Goals / Non-Goals

**Goals:**

- Neither recovery touches a row another running transport may still settle — the property both mechanisms
  running at once will require — while today's single-mechanism app loses no photo it does not lose now.
- Every `REQUESTED` row that no transfer can settle is demoted by some check that is guaranteed to run.
- Every decision is in `:domain` and runs under `commonTest`; the adapters report transport facts only.
- One repair operation replaces the delete-and-reset pair, removing the discovery-cursor carve-out.

**Non-Goals:**

- Running both mechanisms at once. The restart repair below is correct only while starting a mechanism means
  the other one is not carrying rows; the phase that runs both must revisit it (see Risks).
- Replacing the per-launch `start()`. The restart repair relies on starts happening; the phase that changes
  launch to compare desired against registered state must keep a start when stray `REQUESTED` rows exist.
- Changing how often the per-cycle pass runs. It stays per cycle.
- Eliminating the late-completion race (a completion delivered after its row was demoted): it costs a
  duplicate upload, which is accepted.

## Decisions

### D1 — The frame is "when can the transfer list be trusted", not "how often"

The original plan ran the check once per process, arguing a strand is a process-death consequence. Two
findings retired that. First, the harm of per-cycle reconciliation under two transports is its **candidate
set**, not its frequency: a pass that only considers its own transport's transfers is safe at any frequency.
Second, the one hazard the pass has — a finished transfer leaving `getAllTasks` before its completion is
delivered, so the pass demotes a row whose success then applies to nothing — is most likely exactly at the
first drain after a relaunch, which is where "once per process" would have concentrated the only run. The
hazard costs a duplicate upload, which is accepted, so no trust gate is added and the pass keeps its current
per-cycle position.

*Alternatives:* once per process at the first drain (concentrates the pass at its riskiest moment); gating on
`URLSessionDidFinishEventsForBackgroundURLSession` (does not fire on a foreground launch; buys nothing once
duplicates are accepted).

### D2 — Two checks, two rules

```
                    REQUESTED rows
          ┌──────────────┴──────────────┐
    staged file (app)             no staged file (PhotoKit, or a file already lost)
          │                              │
per-cycle pass:                  restart repair (at a mechanism's start):
  REQUESTED ∩ lost → FAILED        REQUESTED − (live app tasks) → FAILED
```

| check | when | rule | why it is safe |
|---|---|---|---|
| per-cycle pass | every app-driven cycle | `REQUESTED ∩ lostKeys()` | touches only transfers this transport began and no longer holds |
| restart repair | inside a mechanism's `start()` | `REQUESTED − liveKeys()` (app) · every `REQUESTED` (PhotoKit) | at a start, no other transport can still settle a row |

The restart repair is the rule the per-cycle pass has today, moved to the one moment it is true. It needs no
staged-file marker, so it also covers every way a marker can be lost: rows stranded before this change
shipped (an older build's sweep already deleted their files), a cancellation whose completion never arrived
after `cancelAll` deleted the file, and the rows a hand-off from PhotoKit leaves.

*Alternative considered and rejected:* demote `REQUESTED ∧ ¬staged` at the hand-off only. It misses the
reverse direction — the app-driven mechanism's `stop()` cancels its tasks, a crash lands before the files go,
and PhotoKit runs from then on, so no app cycle ever demotes those staged, dead rows.

### D3 — The restart repair lives in each mechanism's `start()`, not in the hand-off wrapper

`RelinquishThenRun` wraps the app-driven mechanism only on an OS that carries PhotoKit, so a repair placed there
never runs below iOS 26.1 — where rows stranded by an old build, or by a missed cancel completion, still need
it. The repair belongs to the start, as PhotoKit's own repair already belongs to its start ritual.

### D4 — The app-driven restart repair is a flag the cycle consumes

`start()` runs outside the pump's single flight, and a cycle may already be running (a background-session
relaunch drives the pump directly; `UploadArm.switchTo` sets the current mechanism before calling `start()`).
A repair that reads `liveKeys()` and then the `REQUESTED` rows outside a cycle can interleave with a
`createJob` and demote a transfer that is live. Instead `start()` marks a restart pending on the cycle, and the
next cycle to reach its stranded pass applies the restart rule once, then the per-cycle rule thereafter.

```
UrlSessionUploadController.start():   cycle.signalRestart(); pump.onStart()
UploadCycle.reconcileStranded():      if consumeRestart(): REQUESTED − liveKeys()
                                      else:                REQUESTED ∩ lostKeys()
                                      then discard(lostKeys())
```

Coalescing in the pump is irrelevant: whichever cycle runs next consumes the flag. A cycle that never reaches
the pass (unreadable or not-joined membership) leaves it pending, which is correct.

*Alternatives:* a `:domain` function the controller calls before `pump.onStart()` (the ordering lives in the
untested shell, and it races); an injected step in `BackgroundUploadPump.onStart()` (the pump would learn about
ledger repair, and a coalesced start must carry the step through the running drain); a mechanism-table wrapper
(the same race as the first, and absent below 26.1 unless wrapped everywhere).

The flag is in memory. A process that dies before a cycle consumes it loses it; the next process's `start()`
sets it again.

### D5 — The PhotoKit restart repair is a bulk demote between the disable and the enable

PhotoKit's cycle runs in the extension, so no app-process cycle can consume a flag for it, and its repair must
sit after the disable (which wipes the OS's jobs) and before the enable (after which the extension may record
fresh `REQUESTED` rows). With no app-driven transfer running at that point — a start of PhotoKit is preceded by
the app-driven mechanism's `stop()` wherever both exist — every `REQUESTED` row is unsettleable, so the repair is
the whole `REQUESTED` set.

It runs in the app process, where on iOS ≥26.1 the extension is the recording process. A per-row
`markTerminal` from there would be a second recording process, which `sync-ledger` forbids. A **bulk** state
update is the reset family `clearRequested` already belongs to, so the repair replaces that operation:

```sql
-- was:  DELETE FROM ledgerRow WHERE state = 'REQUESTED'
demoteRequested: UPDATE ledgerRow SET state = 'FAILED' WHERE state = 'REQUESTED'
```

The off-main, bounded-retry, awaited helper (`ClearRequested.kt`) stays, renamed for the demote, and the
enable still waits for it.

### D6 — Demoting instead of deleting removes the discovery-cursor reset, and with it the carve-out

`clearRequested` deleted the rows, so they could only return through discovery, and a settled cursor would
never re-surface them — hence the reset. A `FAILED` row needs a job (`LedgerState.needsJob`), so the ledger's
work read returns it on the next cycle with no walk. The reset has no remaining purpose, so it is removed, and
`upload-lifecycle`'s carve-out permitting a `stop()` to clear its cursor loses its only instance and is
removed rather than kept as an unused permission. The reconciliation's own cursor clear on a re-baseline is
unaffected.

### D7 — `stop()` becomes the disable, and the narrow hand-off verb collapses into it

With the repair in `start()`, PhotoKit's `stop()` is `setEnabled(false)` alone — which is exactly what
`deregister()` is. The two verbs exist only because `stop()` used to carry a repair the hand-off must not run;
that reason is gone. The relinquish binding uses `stop()`, and `deregister()` is deleted. The leave path
(`stopAll()`) no longer repairs: its rows stay `REQUESTED` until the next start, which is the only moment
anything could upload them again.

### D8 — The transport reports lost transfers and discards on the cycle's word

`BackgroundTransfer` gains two members beside `liveKeys()`:

- `lostKeys(): Set<String>?` — transfers this transport began and no longer holds, including across process
  death; `null` where it cannot tell. The app-driven adapter answers staged files minus live tasks. PhotoKit,
  the simulator job queue and the world fake answer `null`, each stating why at its definition, as they do for
  `liveKeys()`.
- `discard(keys)` — drop whatever the transport kept for those transfers (the staged files).

The cycle calls `discard(lostKeys())` after its pass in **both** modes. After the pass no lost key's row can
still be `REQUESTED` — it was demoted, or it was not `REQUESTED` to begin with (an orphan left by a process that
died between recording the outcome and deleting the file) — and no new file is staged until `createJob`, later
in the same single-flight cycle. This is the orphan sweep, moved to the one place that knows a row has left
`REQUESTED`, which is why `sweepStaging()` and its call at `start()` are removed.

A combined `Held(live, lost)` snapshot was considered and rejected: each rule reads only one set, so there is
no moment at which the two must agree. `release` was rejected as a name: `OsReceipt` already means releasing
an OS completion handler.

The rule's inputs stay `Set<String>` arithmetic in `StrandedKeys.kt`, so both modes are asserted without a
device.

### D9 — Placement of each responsibility

| responsibility | module | tested by |
|---|---|---|
| both stranded rules, the restart flag, discard ordering | `:domain` `feature/upload` (`UploadCycle`, `StrandedKeys`) | `UploadCycleTest`, `StrandedKeysTest` |
| PhotoKit disable → demote → enable | `:domain` `feature/upload` (`OsDrivenUploadMechanism`) | `OsDrivenUploadMechanismTest` |
| `demoteRequested` semantics | `:domain` `ports/` + both stores | `LedgerStoreContract` (`:test:world`) |
| staged-minus-live, file deletion | `:adapter:ios:app-only` | device (mechanism only; no decision) |
| calling `signalRestart()` before `pump.onStart()` | `:app:ios` (wiring) | none; order-independent by D4 |

## Risks / Trade-offs

- **[Late completion after a demotion]** A transfer finishes, leaves `getAllTasks`, is demoted, and its
  success then applies to nothing → a duplicate upload. Most likely at a relaunch's first cycle. → Accepted;
  the PUT is idempotent. The adapter's "applied to NO row" warning is the device-log fingerprint.
- **[A late cancel completion hits a recreated transfer]** `stop()` cancels key *k*, a start demotes and
  recreates *k*, then the old task's cancellation arrives: it records `FAILED` over the new `REQUESTED` row and
  deletes the new staged file. Requires a stop→start inside the delegate's delivery latency. → Accepted: a
  failed attempt that is retried, or a duplicate upload. Pre-existing.
- **[Restart flag lost with the process]** → The next process's `start()` re-arms it. Today's per-launch
  permission replay guarantees such a start on a cold foreground launch.
- **[The later launch-comparison phase removes the per-launch start]** A crash between a hand-off's
  deregistration and the next cycle leaves stray `REQUESTED` rows while desired and registered state already
  agree, so no start runs. → Recorded for that phase: stray `REQUESTED` rows while no transport can settle them
  must count as a reason to start.
- **[Running both mechanisms]** The app-driven mechanism's restart repair would demote PhotoKit's live rows,
  and PhotoKit's bulk demote would demote the app's live rows. → Recorded for that phase: the restart repair is
  correct only where a start means the other mechanism is not carrying rows; there it must narrow to
  `REQUESTED ∩ lost` (app) and exclude staged-and-live keys (PhotoKit).
- **[The extension records during the PhotoKit demote]** A running `process()` could record `REQUESTED` for a
  job created just before the disable. → Same exposure as today's `clearRequested`; the next start repairs it.
- **[Leave no longer repairs]** Rows stay `REQUESTED` after a leave with no rejoin. → Harmless: nothing
  uploads without a membership, and every rejoin starts a mechanism.
- **[Status counts]** `REQUESTED` rows the pass used to demote each cycle now stay `REQUESTED` until a start if
  they are not lost by this transport (e.g. after a hand-off). → Bounded by the next start, which a hand-off
  itself performs.

## Migration Plan

No data migration. On the first start of a build carrying this change, the restart repair demotes every
`REQUESTED` row without a live transfer, including rows whose staged file an older build's sweep already
deleted. Rollback is a plain revert: an older build's ledger-wide pass and `clearRequested` handle `FAILED`
rows, which they already produce.

## Device Verification

Measured on the SE2 (iOS 26.6), rig build, 2026-09-16.

- **Hand-off from PhotoKit (the case this change exists for).** The extension created OS jobs; a leave ran the
  OS-driven `stop()` (the disable alone, touching no row); a re-join of the same event under the app-driven
  mechanism ran `url-session.start()`, and its next cycle recorded 16 `REQUESTED` rows `FAILED` with the restart
  rule, all within one second. 143 later cycles recorded nothing by either rule; all 16 keys re-uploaded through
  the app-driven transport; no completion applied to no row.
- **Cold launch resolving PhotoKit** ran relinquish (`url-session.stop`) → `photokit.start`: disable → demote →
  enable, 27–72 ms, registration enabled afterwards.
- **Force-quit mid-transfer** (two transfers in flight) did **not** strand either row silently: on relaunch, as
  the session was re-adopted, iOS delivered both completions as `NSURLErrorDomain:-999`, and the delegate
  recorded them `FAILED` before any repair ran. So on this device a force-quit's cancellations are delivered,
  late, at the next session adoption; the restart repair remains the backstop for a completion that is not.
- **Observed, pre-existing, out of scope:** those late `-999` completions re-entered the app-driven pump through
  the adapter's `onTerminal` wiring, which does not go through the arm, so the app-driven mechanism kept creating
  jobs after the arm had relinquished it to PhotoKit — two recording processes over one ledger. This predates
  this change (the wiring is unchanged) and is recorded for the phases that run both mechanisms.

## Open Questions

- Resolved by the device check above for the force-quit path: a cancelled background-`URLSession` task's
  `NSURLErrorCancelled` completion is delivered — late, on the next session adoption. Whether an in-process
  `cancelAll` delivers it promptly was not observed (no transfer was in flight at the stops measured). The
  `cancelAll` comment no longer asserts either way, and correctness does not depend on it (D2).
