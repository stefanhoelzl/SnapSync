# sync-status-screen — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: The not-started health advances on a foreground tick

The presentation container SHALL re-evaluate `startsAt > now` on a **one-minute tick**, which SHALL run
**only** while the app is foregrounded **and only** while the event has not yet started — it SHALL stop
itself once the start passes, and SHALL NOT run for the entire life of a joined event. The tick is
necessary because the `NotStarted` health depends on **wall-clock time**, not on any ledger event, so no
snapshot emission would ever retire it when the start passes.

The tick SHALL live in the **presentation** layer (which already owns a coroutine scope and the injected
time source), **not** in the `feature/status` projection. The status projection SHALL remain a clock-free, read-only
ledger→`SyncStatus` projection: it has no notion of wall-clock time today, and giving it one to render a
label would be the wrong seam.

A staleness of up to one minute is accepted: nothing of the member's can upload before the start in any
case, so a briefly-late transition costs nothing but the label.

#### Scenario: The clock line retires itself when the start passes
- **WHEN** the app is foregrounded showing `NotStarted` and the event's `startsAt` passes
- **THEN** within one minute the health re-derives to the snapshot-driven value (`InSync` / `Syncing`)
  without any ledger event having occurred

#### Scenario: The tick does not run after the start
- **WHEN** the event has already started
- **THEN** no tick is scheduled, the health deriving from the snapshot alone

#### Scenario: The status projection stays clock-free
- **WHEN** the `feature/status` projection is inspected
- **THEN** it reads only the ledger and holds no clock, the not-started derivation living entirely in the
  presentation reduction

