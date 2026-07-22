# sync status screen Specification

## ADDED Requirements

### Requirement: The joined layer marks an ended event

When the membership carries an `endsAt` and `now > endsAt`, the `Joined` state's health line SHALL carry
an **"Event ended"** marker that **prefixes** the regular one-line status (e.g. `Event ended · In sync`,
`Event ended · Synchronization ongoing…`). The marker SHALL be purely **informational**: it SHALL NOT
change any arrow, count, or the underlying health value, and sync SHALL continue during the backend grace
window (capability `event-limits`) exactly as before the end passed — the event's lifecycle end is closed
**server-side**, and the client asserts no lifecycle enforcement. The marker SHALL render in the existing
single status-line slot, so the joined layer never grows a second status line.

The marker SHALL be computed from the now-stored `endsAt` and the existing foreground **`nowTick`**,
symmetric with the existing not-started line: it SHALL advance on the one-minute foreground tick, so an
event that ends while the screen is foregrounded gains the marker within one minute without any ledger
event. When the membership carries **no** `endsAt` (a pre-backfill legacy membership, capability
`event-rejoin-reconciliation`), no "Event ended" marker SHALL be shown.

#### Scenario: A past end prefixes the health line
- **WHEN** config is present, the membership's `endsAt` is before `now`, and the snapshot-derived health
  is `InSync`
- **THEN** the status line reads `Event ended · In sync`, the arrows and health value are unchanged, and
  sync continues

#### Scenario: The marker is informational while syncing continues
- **WHEN** the membership's `endsAt` is past and work still remains (a `Syncing` health)
- **THEN** the "Event ended" marker prefixes the `Syncing` status and no upload or download is halted by
  the marker — the end is enforced only server-side

#### Scenario: The marker appears on the foreground tick when the end passes
- **WHEN** the app is foregrounded showing a joined event whose `endsAt` then passes
- **THEN** within one minute the health line gains the "Event ended" marker, computed from `endsAt` and
  the foreground `nowTick`, with no ledger event having occurred

#### Scenario: A membership without an endsAt shows no marker
- **WHEN** config is present but the membership carries no `endsAt` (a pre-backfill legacy membership)
- **THEN** no "Event ended" marker is shown, and the status line is the regular one-line health
