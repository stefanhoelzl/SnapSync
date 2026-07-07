## Context

`LeaveEvent` (`capability/membership`) is the local-only inverse of join. Today it runs, awaited, in
order: **disable producer → notify backend (`DELETE /events/<id>/devices/<id>`) → clear config**. The
screen leaves the joined layer only when `ConfigStore.clear()` fires `ConfigSource.config` to `null`,
which sits behind the awaited DELETE. So a slow DELETE freezes the joined screen after the user confirms
"Leave" — the only button in the app whose screen change blocks on a network call (Create sets
`CreationStatus.InFlight` and Join sets `JoinPhase.Committing` *before* awaiting their requests).

Facts established while scoping:
- `disableExtension()` is a **local** op (`setUploadJobExtensionEnabled(false)` + clears the REQUESTED
  flag) — not network — so keeping it awaited before the clear stays instant.
- `SnapSyncRoot` owns a process-lifetime `SupervisorJob` on `Dispatchers.Main` (deliberately outlives
  Compose) — the natural scope for a fire-and-forget DELETE that must survive the flip.
- `ConfigStore` is command-only (`save`/`clear`); the synchronous `eventId` read comes from
  `ConfigSource.config.value`. The composition already reads `config.config.value?.eventId` for notify.
- The DELETE is documented (spec `leave-event`, endpoint `event-leave-endpoint`) as best-effort,
  abandon-leak-accepted, self-healing — nothing is supposed to depend on it.
- `StatusContainerHost` injects leave as a plain `suspend () -> Unit` and also calls it from the switch
  path (`commit(withLeave = true)`); both benefit from the same change with no container edit.

## Goals / Non-Goals

**Goals:**
- The Leave button flips the screen to the setup gate immediately, independent of the DELETE's latency.
- Preserve every best-effort / no-rollback / self-heal guarantee of the current leave lifecycle.
- Keep all logic in the tested `capability/membership` module; confine wiring to the untested app shell.
- Extend the same non-blocking behavior to the switch path for free.

**Non-Goals:**
- No `LeavingEvent` UiState, spinner, toast, or any new UI affordance — the flip is the feedback.
- No change to `StatusContainerHost`'s `leave: suspend () -> Unit` seam or the leave confirm dialog.
- No backend / endpoint changes — the DELETE is identical, only dispatched later and un-awaited.
- No change to Create or Join.

## Decisions

### D1 — Reorder to disable → clear → background-notify

Move `ConfigStore.clear()` ahead of the notify and dispatch the notify fire-and-forget:

```
suspend fun leave() {
  val id = configSource.config.value?.eventId   // snapshot BEFORE clear (sync, race-free)
  step("disable") { disableExtension() }         // local, awaited — producer-race invariant
  step("clear")   { config.clear() }             // ← ConfigSource → null → screen flips here
  id?.let { scope.launch { step("notify") { notify(it) } } }   // fire-and-forget DELETE
}
```

The two self-heal invariants survive: **disable stays before clear** (no producer races teardown), and
the **notify still gets a valid `eventId`** because it is snapshotted before the clear. *Alternative
considered:* an optimistic `UiState` set in the container the instant Leave is tapped — rejected: it
duplicates the `config == null` signal (two sources of "show setup"), needs reconciliation if teardown
fails, and puts logic in the presentation layer instead of the tested use-case.

### D2 — Fire-and-forget on the app-lifetime SupervisorJob

`LeaveEvent` gains an injected `CoroutineScope`, bound to `SnapSyncRoot`'s `SupervisorJob`. The DELETE
must outlive the flip to the Create screen, so it cannot ride a scope tied to the joined view. *Alternative
considered:* launch inside the composition's notify lambda — rejected: the eventId snapshot must happen
synchronously *before* the clear, inside the use-case's own frame, or `scope.launch { notify() }` races
`clear()` and the closure reads a null config. Owning the launch in `LeaveEvent` keeps the sequencing
correct and under test.

### D3 — Notify takes the eventId; LeaveEvent reads ConfigSource

The injected notify becomes `suspend (eventId: String) -> Unit` and `LeaveEvent` gains a `ConfigSource`
to snapshot `config.value?.eventId` synchronously. This is what makes the background dispatch race-free.
The composition binds `notify = { id -> leaveNotifier.leave(id, deviceId) }`.

### D4 — Notify fires unconditionally after clear

The background notify is dispatched even if `clear()` threw (caught by the existing `step` wrapper),
matching the current "each step independent, best-effort" contract. The resulting transient state
(backend told the device left while `clear` failed and it is still joined) already exists in today's
notify-then-clear order and self-heals when the producer re-enables and re-writes the device manifest.
*Alternative considered:* gate the notify on clear success ("only tell the backend once we've actually
left") — rejected to keep the delta minimal and the step-independence philosophy intact.

## Risks / Trade-offs

- **A backgrounded DELETE could be lost if the process dies before it runs** → Already the accepted
  abandon-leak (best-effort notify); the backend GC / a later rejoin reconcile it. No worse than today's
  offline-at-leave case.
- **Transient "backend gone, locally joined" after a failed clear** (D4) → Pre-existing in the current
  order; self-heals on the next producer enable. Documented in the spec's best-effort requirement.
- **Constructor signature change to `LeaveEvent`** → Confined to the untested app-shell composition root;
  `StatusContainerHost`'s seam is unchanged, so presentation/harness/tests construct unchanged.
- **Switch path momentarily has `config == null` between clear and the new provision** → Unchanged from
  today (clear already precedes `commitJoin`); the pending overlay renders `JoiningEvent(Committing)`
  either way. The reorder only makes it happen sooner.
