## Why

Switching events is the one join path a member cannot configure. The switch confirmation is a compact
dialog with no pickers, so it commits a hard-coded membership — direction `Both`, the capture-date range
at the event's full window, album off — while every other join route lets the member choose all three.
Those choices are fixed at join and re-scanning the already-joined event short-circuits as
`AlreadyJoined`, so a switching member has **no route at all** to any other shape: a guest who wants to
receive an event's photos without sharing their own must leave, then scan again.

The fix is not a bigger dialog. Confirming the switch should leave the current event and hand the member
the **regular join screen** for the new one — the surface that already answers every one of these
questions.

## What Changes

- The switch confirmation's **Confirm becomes the leave**, and nothing else. It no longer commits a join.
- Once the leave clears the config, the still-pending join reduces — through the reduction rung that
  already exists — to the **full-screen join surface** for the new event: Share/Receive switches, the
  three-option capture-date cutoff, the album opt-in, the live shareable count, and the photo-access
  explainer when permission was never asked.
- The **photo-access explainer becomes reachable on a switch**. Its rule is unchanged (`config` absent
  **and** permission `NOT_DETERMINED`); what changes is that the gate's loaded-phase derivation now runs
  at **two** points — when the details fetch resolves, and again once the switch's leave clears the
  config.
- The switch dialog's body drops the participation-reset promise (now false — the member picks next) and
  the shareable-count sentence (now shown on the join surface, against the range the member actually
  chose). It states only which event is left and which is joined.
- **Cancelling on the join surface after the leave leaves the device in no event**, landing on the create
  screen — the existing rule for cancel with no config. Rescanning rejoins.
- Structurally unreachable code is removed: the switch dialog's commit-failure branch and its
  hand-remembered bounds, `commit`'s `withLeave` parameter, and the harness's switch `CommitFailed`
  preset. `onConfirmSwitch` loses its parameters — it chooses nothing for anyone.
- No **BREAKING** change: no persisted shape, wire format, or backend contract moves.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `join-event`: the switch requirement is rewritten (confirm = leave; the join then runs through the
  regular gate with full pickers); the loaded-phase **derivation** is named and runs at two points, so
  `A switch never explains` becomes "not yet" plus a post-leave explainer scenario; the requirement that
  the count renders on the switch confirmation surface is deleted, the surface having become the join
  surface that already carries it; the enrollment requirement drops its now-undistinguishable "or a
  switch to a different `eventId`" clause.
- `desktop-test-harness`: the switch presets lose `CommitFailed` (unreachable once the leave precedes any
  commit); the note that `ExplainAccess` needs no switch preset keeps its conclusion but replaces its
  reason — the explainer is now reached after the leave, as an ordinary join state the join presets
  already cover.

## Impact

- `ui/presentation` — `StatusContainerHost`: `onConfirmSwitch` runs the leave and re-derives the phase;
  `commit` loses `withLeave`; `readyOrExplain` becomes the named derivation, called from two sites.
- `ui/screens` — `StatusScreen`: `SwitchDialog` shrinks to Loading/Ready/NotFound/LoadFailed with new body
  copy. The `pendingSwitch` suppression of the settings gear and rename pen is **unchanged** — the dialog
  still precedes the leave.
- `app/desktop` — `PanelController`: the switch `CommitFailed` preset and its button go.
- `:domain` — **untouched**. `LeaveEvent`, `JoinEvent`, `Provision`, and `switchDecision` are called
  exactly as before; `UserCommands.leave` already cancels downloads and prunes non-terminal rows ahead of
  the leave, so that ordering rides along unchanged.
- Backend, storage, and wire formats — untouched.
- `openspec/specs/join-share-count/spec.md` — a **Purpose prose** correction only: it names "the join,
  switch, and reconfigure surfaces", and the switch surface no longer renders a count of its own. No
  requirement of that capability changes (its switch rules lived in `join-event`), and a Purpose-only
  edit has no delta form here, so it is carried as an explicit task instead.
- Tests: `StatusContainerHostTest` for the state machine (leave ran, pending survived, phase re-derived,
  failed clear stays on the dialog); `JoinGateIntegrationTest` for the outcome that motivates the change —
  a switch honouring a non-default direction and album against the world.
