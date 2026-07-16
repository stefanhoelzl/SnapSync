## ADDED Requirements

### Requirement: The world composes the real cycle rather than mirroring its assembly

The world SHALL drive an upload cycle by constructing the real cycle and invoking it, supplying the same
ports a composition root supplies. It SHALL NOT re-implement the roots' assembly — the membership decision,
the leave-side reconciliation, the engine construction, and the hook wiring — in harness code.

A hand-written mirror of a composition root drifts from it, and drifts silently: before the app-driven
tier's reconciliation was fixed, the world **already reconciled** on its mirrored path while the real tier
did not. A mirror that is more correct than production is worse than one that is wrong, because it stays
green while the defect ships. What the world may keep is what the roots keep — translation from its own
in-memory state into the shared decision's arguments — plus a tier's genuinely tier-specific residue, which
it SHALL name as such (the OS-invoked tier's pending→processing requeue).

#### Scenario: The world's cycle is the real cycle
- **WHEN** the world runs an upload cycle
- **THEN** the cycle that runs is the shared upload cycle, reaching its entry decision through the same
  read the real tiers use

#### Scenario: The world cannot invent a membership the real tiers require
- **WHEN** the world runs a cycle with no joined event
- **THEN** no cutoff is substituted on its behalf; the cycle takes its not-joined outcome, as a real tier
  would

### Requirement: The world can model an unreadable membership

The world SHALL be able to present its membership as **unreadable**, distinctly from absent, so the skip
outcome (capability `upload-lifecycle`) is reachable from tests over the world.

This is the state a real device reaches on a background wake before first unlock, and it is the state three
shipped bugs have turned on. A world whose membership is a nullable cell can express only joined or absent,
so the outcome that matters most is the one no test can reach — the harness models the states that work and
omits the state that breaks.

#### Scenario: An unreadable membership is distinct from an absent one
- **WHEN** the world's membership is set unreadable and a cycle runs
- **THEN** the cycle skips, the joined-event marker is intact, and the ledger, discovery cursor, and
  object store are untouched

#### Scenario: An absent membership still drives the leave path
- **WHEN** the world's membership is cleared and a cycle runs
- **THEN** the leave-side reconciliation runs and the joined-event marker is cleared
