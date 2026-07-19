## MODIFIED Requirements

### Requirement: The migration beacon is red until the migration completes
Migration distance SHALL be measured by a dedicated module detached from `check` and reported by
the NON-required `verify` job of the `architecture` workflow: the job SHALL fail while any per-law
burn-down count is nonzero (writing the per-law table to the job summary before failing) and SHALL
pass exactly when every count is zero. The check SHALL NOT be required and SHALL NOT gate any
merge; the release guard in `ios-appstore-promote.yml` and `/ship`'s watcher SHALL judge REQUIRED checks
only, with the required set derived from branch protection at run time — never a name list — so
this and any future informational check is tolerated automatically, and the filter degrades in
the strict direction (unresolvable required set ⇒ every check counts). At completion each gate
moves into the gating module and the beacon module is deleted.
Accepted risk unchanged, on record: during the migration nothing GATES new violations — the
beacon makes them visible (red, with numbers), not blocked.

#### Scenario: A release during the migration
- **WHEN** `ios-appstore-promote.yml` is dispatched while the beacon is red
- **THEN** the release guard evaluates required check-runs only, ignoring the red beacon and
  any other non-required check
