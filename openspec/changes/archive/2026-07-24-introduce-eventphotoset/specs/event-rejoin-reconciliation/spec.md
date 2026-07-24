## ADDED Requirements

### Requirement: Reconcile no longer backfills an absent ceiling

The reconcile path SHALL NOT carry an absent-ceiling backfill or an unbounded-until-backfilled allowance,
because the capture-date ceiling is now required on every persisted membership (capability `join-event`).
Any membership that reaches this change's build already carries a concrete ceiling (backfilled by
`decouple-event-window-from-lifetime` before this change deploys). The reconcile continues to refresh the
event name and other membership details unchanged; it simply has no absent ceiling to fill.

#### Scenario: No absent-ceiling branch remains

- **WHEN** the reconcile path is inspected
- **THEN** it contains no branch that treats an absent capture-date ceiling as unbounded or backfills one
