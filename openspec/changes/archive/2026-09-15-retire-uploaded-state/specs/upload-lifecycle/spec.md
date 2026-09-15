## MODIFIED Requirements

### Requirement: The cycle's publication is decided by its outcome

The upload cycle SHALL decide what it publishes from its own stated outcome, in one place, rather than
by which statement returned. What it publishes means the enumeration audit line and the device manifest.
The decision SHALL be exhaustive over the outcomes a cycle can have, so a new outcome cannot inherit a
publication policy nobody chose for it.

It publishes no completion notify, no promotion of uploaded rows, and no event-album placement. There is no
notify on the versioned device API; a successful upload is recorded settled where the platform reports it
(capability `sync-ledger`); and own-photo album placement is a write to the member's own library made when
the cycle enqueues work (capability `event-album`), not something the event can see.

No path SHALL be able to return a cycle result without passing through that decision.

This exists because five publications were previously reachable only by falling through to the end of
the cycle, so any early return silently withheld all five — and the two early returns that a device
with a backlog takes on every cycle withheld them permanently, with no error and no log line.

#### Scenario: A new cycle outcome must state what it publishes

- **WHEN** a new outcome is added to the cycle's result vocabulary and the publication decision is not
  updated
- **THEN** the build fails, because the decision is exhaustive with no fallback branch

#### Scenario: Every exit publishes

- **WHEN** a cycle ends by any route — unreadable membership, no membership, deferred reconciliation,
  declined direction, job limit reached, or fully drained
- **THEN** the publication decision runs for that outcome, publishing exactly what that outcome calls
  for

#### Scenario: Publishing settles nothing

- **WHEN** a truncated or drained cycle publishes
- **THEN** it writes the device manifest and the enumeration audit line, and changes the state of no ledger
  row
