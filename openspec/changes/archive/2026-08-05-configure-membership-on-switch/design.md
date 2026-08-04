## Context

Joining an event is where a membership is **configured**: the participation direction (derived from the
Share and Receive switches), the capture-date range, and the album opt-in are all chosen on the join
surface and all fixed at join. `join-event` says so, and re-scanning an already-joined event
short-circuits as `AlreadyJoined`, so the choices cannot be revisited by rescanning.

Switching events was carved out of that. A link for a different event decoded while joined produced
`Joined.pendingSwitch`, rendered as a compact `AppDestructiveConfirmDialog` over the joined layer, whose
confirm ran `commit(withLeave = true)` — `LeaveEvent.leave()` then `JoinEvent.join()` — with values the
dialog supplied itself: `Direction.Both`, the range `[startsAt, endsAt]`, `saveToAlbum = false`. There
were no pickers, so those were the only membership a switch could produce.

The carve-out accumulated its own rules. A switch had its own confirm surface, its own defaults, its own
commit path, its own commit-failure retry, its own share-count rendering rule, and its own exemption from
the photo-access explainer ("a switch never explains" — justified by *"a compact dialog is no place for
an explanation"*). Six rules, all downstream of the surface being a dialog.

This design removes the surface, and with it the rules.

## Goals / Non-Goals

**Goals:**

- A member switching events configures the new membership exactly as any other joiner does.
- The switch stops being a join **variant** and becomes what the mission already calls it: leave, then
  join.
- Reuse the existing reduction rather than adding state. The full-screen join surface should appear
  because the config is gone, not because a flag says so.
- Delete every rule the carve-out needed, rather than porting it.

**Non-Goals:**

- Changing `LeaveEvent`, `JoinEvent`, `Provision`, or `switchDecision`. `:domain` is untouched.
- Changing the `autoJoin` path. It has no screen to configure on and stays a one-shot leave + commit.
- Making the pending join durable. It stays in memory.
- Backend, storage, or wire-format change of any kind.
- Concurrent multi-event membership. This change moves *toward* it (see D1) without building for it.

## Decisions

### D1. The switch dialog's confirm is the leave, and nothing else

`onConfirmSwitch()` calls `commands.leave()` and leaves the pending join in place. `LeaveEvent` clears
the config synchronously (the backend `DELETE` is dispatched fire-and-forget on the app-lifetime scope),
so `ConfigSource` goes `null` and `reduceFrom`'s existing top rung — *config absent, pending join
present → `UiState.JoiningEvent`* — renders the full-screen join surface for the new event. The dialog
dismisses itself by the state changing under it.

*Alternatives considered.* **Leave on the join surface's Join tap** (the dialog only navigates) would let
a member cancel back into their old event, but makes the dialog's "you'll leave A" a statement about the
future rather than the act, and keeps the atomic leave+join commit path alive. **Render `JoinScreen` from
`Joined.pendingSwitch`** would leave two `UiState` shapes rendering one surface, with the joined layer
still underneath. **A new `JoinPhase.ConfirmSwitch`** (parallel to `ExplainAccess`) was considered and
rejected as unnecessary: the leave already produces exactly the state discriminator needed, so a phase
would encode a fact the config already carries.

The chosen shape is also the one that *undeepens* the single-membership assumption `join-event`'s Purpose
asks new work not to deepen. The old switch was an artifact of that assumption — an atomic operation that
exists only because a device can be in one event. Leave and join are now independent again; if concurrent
membership ever arrives, the delta is "drop the leave gate", not "unpick a bespoke commit path".

### D2. The loaded-phase derivation is named, and runs at two points

The rule choosing between the explainer and the confirm phase is unchanged — `config` absent **and**
permission `NOT_DETERMINED`. Both conditions keep doing real work: `config` absent is precisely what keeps
the *pre-leave* switch dialog out of the explainer. What changes is that the rule is **evaluated twice**:
when the details fetch resolves, and again once the switch's leave has cleared the config.

Naming the derivation (rather than enumerating "the fetch, and after a switch's leave") follows this
spec's own idiom — the capture-date clamps are pinned at one choke point *"so that every entry path is
covered"*, with the paths named as illustration. A future entry point then inherits the explainer instead
of being a path someone forgot to add to a list.

The derivation deliberately stays **transition-shaped**, not an invariant. Phrasing it as *"the confirm
surface SHALL NOT be presented while config is absent and permission is `NOT_DETERMINED`"* would read as
continuously true and contradict the pinned snapshot semantics — a permission change while the explainer
is on screen must not move the phase.

Note the derivation is a **no-op for every permission except `NOT_DETERMINED`**: `GRANTED`, `LIMITED` and
`DENIED` all yield the confirm phase at both points.

### D3. Re-derive *after* the leave, guarded on the config actually being gone

`LeaveEvent` is best-effort: each step is wrapped, and a failing `ConfigStore.clear()` is logged and
swallowed. So the phase is re-derived only when `config.value == null` afterwards. If the clear failed,
the phase stays at the confirm phase and the dialog simply re-renders — the member can tap Switch again.

*Alternative considered:* set the phase **before** the leave (knowing the config is about to go), which
avoids a possible one-frame render of the plain confirm surface before the explainer replaces it. Rejected
because of the failure mode: on a failed clear the state would be `Joined(pendingSwitch = ExplainAccess)`,
and the dialog's `ExplainAccess` branch renders nothing — the dialog would silently vanish, leaving an
invisible pending join with no way to retry. A one-frame flash for `NOT_DETERMINED` members is the cheaper
cost.

### D4. A failed leave is not reported

Accepted as-is: the joined layer's own Leave button already behaves this way (clear fails → still joined,
no error). A failed config write is a file/keychain failure with no user-actionable remedy, and reporting
it would require `LeaveEvent.leave()` to return a result — changing a `:domain` use-case signature to make
switching stricter than leaving.

### D5. Cancelling after the leave lands the member in no event

This follows `join-event`'s existing rule: cancel discards the pending join and returns to the base
screen, which is the create layer when no config is present. Rescanning the invite rejoins. No new state
and no new copy; the dialog named the event being left.

### D6. The dialog states only which event is left and which is joined

The old body promised the participation reset — *"You'll share photos you take and receive everyone's"* —
which is now false, since the member picks on the very next screen. The appended shareable count also goes:
it was computed for a range the member has not chosen yet, and the join surface renders the count live
against the range they do choose.

### D7. The pending join stays in memory

A kill mid-flow lands on the create screen; rescanning the invite rejoins. Persisting it would make a
transient UI intent into durable state behind a port, with its own staleness rules, for a case a rescan
already fixes.

### D8. Unreachable code is deleted, not kept defensively

With the leave preceding any commit, a commit failure can never occur while a config is present. The
dialog's `CommitFailed` branch, its hand-remembered cutoff/ceiling, `commit`'s `withLeave` parameter
(`autoConfirm` calls `commands.leave()` itself, so `onConfirmSwitch` was its only caller), and the
harness's switch `CommitFailed` preset all go. Kept: the dialog's `ExplainAccess` branch, still required
for exhaustiveness and still unreachable; and `onCancelSwitch` distinct from `onCancelJoin`, because they
are now genuinely different acts — staying in the current event versus ending up in none.

## Risks / Trade-offs

**A stale `DELETE` can depart a re-joined device.** The switch's leave dispatches
`DELETE /events/A/devices/D` fire-and-forget. Cancelling on the new event's join surface and rescanning
the *old* event is now a supported path, so the re-enrollment `PUT` can land while that `DELETE` is still
in flight; the `DELETE` then copies the fresh (empty) manifest to `D.left.json` at a newer timestamp and
deletes the active one, and `lifecycle.ts`'s last-write-wins marks the device departed.

→ **Accepted, not mitigated.** It is non-destructive (the leave endpoint is rename-only, with no
last-member reap and no leave-time GC — the event survives to the nightly sweep), the window is a single
in-flight request, and an *offline* leave renames nothing so cannot race at all. The identical race
already exists via the Leave button plus a rescan; this change only makes it reachable by accident rather
than by deliberate double-action. Per direction:

| Re-joined direction | Union impact | Push | Self-heal |
| --- | --- | --- | --- |
| Includes upload | The winning manifest is the empty enrollment one, so the device's stored bytes stop being emitted | Skipped by notify | **Yes** — the next upload cycle rewrites `D.json` at a fresh timestamp. Guaranteed, because enrollment clears `DeviceManifestProducer`'s skip-if-unchanged marker |
| Download-only | None — a download-only manifest is empty by design | Skipped by notify | **No** — no producer, so no rewrite. The membership keeps working via foreground/relaunch reconciliation instead of silent push |

**The no-event window becomes user-paced.** It was milliseconds (leave and join inside one commit); it is
now however long the member spends on the join surface, and survives no process death. → The device is
coherently in no event throughout: `UserCommands.leave` cancels in-flight downloads and prunes
non-terminal rows *before* `LeaveEvent` stops the producer and clears the config, so no half-state exists.
Recovery is a rescan.

**The new event's details go stale while the member configures.** Loaded when the dialog opened,
committed when they tap Join. → Pre-existing and unchanged in kind: a first join is equally user-paced
between its fetch and its commit. The switch path merely inherits it, having previously committed within
milliseconds of the load.

**A one-frame confirm surface before the explainer.** See D3 — accepted in exchange for the failed-clear
behaviour, and only observable for `NOT_DETERMINED` members.
