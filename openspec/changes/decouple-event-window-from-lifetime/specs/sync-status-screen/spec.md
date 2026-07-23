## MODIFIED Requirements

### Requirement: The joined layer marks an ended event

When the membership carries an `endsAt` and `now > endsAt`, the `Joined` state SHALL carry an **"Event
ended"** marker rendered on its **own line, directly above** the regular status line. The marker SHALL NOT
be an inline prefix of the status text, and the two SHALL NOT be joined by a separator into a single
phrase.

Two facts are being stated, and they are unrelated: the event's **capture window** has closed, and the
device's **transfer** is in some state. Rendered inline as `Event ended · Synchronization pending…` they
read as one sentence — a claim *about* the syncing — which is false: sync continues exactly as before,
and it is the window that ended. On a phone-width line the pair also wraps mid-phrase, breaking wherever
the text happens to run out rather than between the two facts. Stacking states each fact once and lets the
status keep the full width it was designed for.

The marker SHALL be styled **subordinate** to the status it labels, so the health value remains the
primary thing read.

The marker SHALL be purely **informational**: it SHALL NOT change any arrow, count, or the underlying
health value, and sync SHALL continue exactly as before the end passed — `endsAt` bounds only which
photos may be uploaded (capability `event-limits`), it closes nothing, and the client asserts no
lifecycle enforcement.

The marker SHALL be computed from the now-stored `endsAt` and the existing foreground **`nowTick`**,
symmetric with the existing not-started line: it SHALL advance on the one-minute foreground tick, so an
event that ends while the screen is foregrounded gains the marker within one minute without any ledger
event. When the membership carries **no** `endsAt` (a pre-backfill legacy membership, capability
`event-rejoin-reconciliation`), no "Event ended" marker SHALL be shown.

#### Scenario: A past end marks the health line on its own line
- **WHEN** config is present, the membership's `endsAt` is before `now`, and the snapshot-derived health
  is `InSync`
- **THEN** "Event ended" is rendered as its own line above the status, the status reads `In sync`
  unchanged, the two are not joined into one phrase, and sync continues

#### Scenario: The marker is informational while syncing continues
- **WHEN** the membership's `endsAt` is past and work still remains (a `Syncing` health)
- **THEN** the "Event ended" marker is shown above the `Syncing` status, no upload or download is halted
  by the marker, and the marker text never merges into the status text

#### Scenario: The marker appears on the foreground tick when the end passes
- **WHEN** the app is foregrounded showing a joined event whose `endsAt` then passes
- **THEN** within one minute the joined layer gains the "Event ended" marker, computed from `endsAt` and
  the foreground `nowTick`, with no ledger event having occurred

#### Scenario: A membership without an endsAt shows no marker
- **WHEN** config is present but the membership carries no `endsAt` (a pre-backfill legacy membership)
- **THEN** no "Event ended" marker is shown, and the joined layer shows the regular status line alone
