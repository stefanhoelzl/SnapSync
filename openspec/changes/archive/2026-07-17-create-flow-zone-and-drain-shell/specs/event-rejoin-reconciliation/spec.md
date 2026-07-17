# event-rejoin-reconciliation — delta for create-flow-zone-and-drain-shell

## MODIFIED Requirements

### Requirement: Reconciliation gate before enabling uploads

The **upload tier** SHALL run a join reconciliation on **its own upload cycle**, before creating any
upload jobs, exactly when an event is configured and its `eventId` differs from a persisted
`joinedEventId` marker. The upload tier is whichever process holds the `LedgerWriter` — the extension
on iOS ≥26.1, the app on iOS 18–26.0. The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join
signal, persisted across the tier's processes. When the configured `eventId` equals the marker, the
tier SHALL NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured,
the tier SHALL neither reconcile nor upload.

The reconciliation SHALL be driven from the **shared upload cycle** (`UploadCycle`, `:domain`
`feature/upload`), not from each tier's composition root, and the cycle SHALL require a reconciliation to
be supplied — a tier that supplies none SHALL NOT compile. Reconciliation is therefore reached on
**every** route to a divergent ledger: a fresh join, an event switch, a leave-then-rejoin, and a
delete-and-reinstall (which no provisioning path observes, because a cold relaunch of an
already-joined app performs no provision).

#### Scenario: Marker mismatch triggers a join

- **WHEN** the upload tier runs a cycle with an event configured whose `eventId` differs from the `joinedEventId` marker
- **THEN** a reconciliation runs before any upload job is created

#### Scenario: Marker match skips the join

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no fetch, enumeration, or seeding occurs and the producer uploads directly

#### Scenario: No event configured does nothing

- **WHEN** no event is configured
- **THEN** the tier neither reconciles nor uploads

#### Scenario: Both tiers reconcile

- **WHEN** a (re)join occurs on iOS 18–26.0 (the app-driven tier) or on iOS ≥26.1 (the OS-driven tier)
- **THEN** the same marker-gated reconciliation runs on that tier's cycle before any upload job is created

#### Scenario: A reinstall reconciles without any provision

- **WHEN** the app is deleted and reinstalled (wiping the App Group ledger) and relaunched into an already-joined event, so no provisioning path runs
- **THEN** the next upload cycle finds no `joinedEventId` marker, reconciles against the per-device listing, and seeds already-stored resources as `COMPLETED` so none re-upload
