## MODIFIED Requirements

### Requirement: Event switch versus re-join

The system SHALL compare a scanned / deeplinked eventId to the persisted config eventId. When it
**equals** the current event and the ledger is non-empty, provisioning SHALL be a no-op (no ledger
reset, no re-seed, the producer is left as is). When it **differs**, the system SHALL reset the
ledger to empty and reconcile for the new event (which then triggers the gate via the now-empty
ledger). After a **leave** (see `leave-event`) the persisted config is absent and the ledger is
empty; a subsequent scan of any event therefore provisions and runs exactly one fresh join
reconciliation for it (there is no previous eventId to compare against, so the empty-ledger gate
drives the join).

#### Scenario: Re-scan of an already-joined event is a no-op
- **WHEN** the scanned eventId equals the configured one and the ledger holds rows
- **THEN** the ledger is not reset, no re-seed occurs, and the producer stays enabled

#### Scenario: A different event resets and reconciles
- **WHEN** the scanned eventId differs from the configured one
- **THEN** the ledger is reset to empty and a join reconciliation runs for the new event

#### Scenario: Scanning after a leave runs a fresh join
- **WHEN** the user has left an event (config absent, ledger empty) and then scans an event QR
- **THEN** the event is provisioned and exactly one fresh join reconciliation runs, seeding any
  already-stored photos as `COMPLETED` before the producer is enabled
