## ADDED Requirements

### Requirement: Invite affordances are rendered UI-only in the harness

The phone frame SHALL render the status screen's invite affordances so they are reviewable offscreen:
when a sync preset forces a joined-layer state (InProgress, NothingToSync, or Complete), the harness
SHALL supply a fixed sample `eventId` so the invite deeplink is non-`null`, and the phone frame SHALL
render the join QR (with the "Scan to join this event" caption) above the hero and the flat icon-only
share action in the bottom action cluster. The harness SHALL NOT perform a real platform share — the
screen's `onShareInvite` callback resolves to a clipboard/log stub (test equipment) — so activating
share exercises the UI and the stub only and mutates no harness state (no config, ledger, or sync cell
changes). The control panel SHALL gain no share control and SHALL gain no editable event-id field (a
fixed sample id suffices); the invite affordances live in the phone frame, like the leave action.

#### Scenario: A joined-layer preset shows the invite QR and share action
- **WHEN** the user activates a joined-layer sync preset (e.g. Complete)
- **THEN** the phone frame renders a scannable join QR with the "Scan to join this event" caption and a
  flat icon-only share action, using the harness's fixed sample `eventId`

#### Scenario: Non-joined presets show no invite affordances
- **WHEN** the user activates a non-joined preset (loading, setup gate, permission, joining, or
  join-failed)
- **THEN** the phone frame renders no invite QR, caption, or share action

#### Scenario: Activating share in the harness is UI-only
- **WHEN** the user activates the share action in the phone frame
- **THEN** the clipboard/log stub runs and no harness state changes (no config, ledger, or sync cell is
  mutated; no real platform share is performed)
