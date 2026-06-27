## MODIFIED Requirements

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered join SHALL: set status `Joining`; fetch the event file list; enumerate the local photo
library for each resource's `(filename, assetId)`; compute the matched subset of local resources whose
`filename` equals a listed filename; seed those as `COMPLETED` via a single atomic ledger reset
(`resetTo`); clear the discovery cursor; then set status `Joined`. The seed records no timestamp — the
join reads no clock. Matching SHALL be by `filename` equality only (no size or timestamp comparison).
Local resources with no matching listed filename SHALL NOT be seeded (the producer uploads them
later). The seed SHALL run with the producer disabled.

#### Scenario: An already-stored photo is seeded completed
- **WHEN** a local resource's filename appears in the event file list
- **THEN** the ledger holds a `COMPLETED` row for that resource

#### Scenario: A locally-present photo not in the list is not seeded
- **WHEN** a local resource's filename does not appear in the event file list
- **THEN** no row is seeded for it and it remains eligible for upload

#### Scenario: Seeding clears the discovery cursor
- **WHEN** a join seeds the ledger
- **THEN** the discovery cursor is cleared so the producer performs a full re-enumeration

#### Scenario: A listed filename with no local match seeds nothing
- **WHEN** the event file list contains a filename that matches no local resource
- **THEN** no row is seeded for that filename
