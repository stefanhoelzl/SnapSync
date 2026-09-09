## ADDED Requirements

### Requirement: A read-only foreground check reports upload state the backend contradicts

The app SHALL, on entering the foreground, compare what the ledger records as landed against the
per-device listing, and SHALL report a disagreement. The check SHALL be **read-only**: it SHALL NOT write
the ledger, SHALL NOT clear the discovery cursor, SHALL NOT set or clear the `joinedEventId` marker, and
SHALL NOT create, cancel, or re-create any upload job. It exists to establish whether the failure occurs
and at what rate, not to correct it.

It SHALL run in the **app** process, from the foreground trigger flow, alongside the download arm's
existing foreground reconcile. That placement is what makes it tier-neutral: on iOS ≥26.1 the upload
mechanism declines the foreground trigger entirely (the OS owns its scheduling), so a check placed on the
upload mechanism would never run there — and that tier is the one whose upload jobs carry no HTTP status,
making a wrong belief most likely.

The comparison set SHALL be the rows the membership's **current policy admits** intersected with the rows
the ledger records as landed (`photo-selection-policy`). The policy filter is required for the report to
mean anything: the scheduled cleanup collects **unreferenced** bytes (capability `scheduled-cleanup`), a
policy-admitted asset is declared and therefore referenced, and without the filter ordinary collection of
a departed event's residue would be reported as data loss.

Two directions SHALL be counted, and only one SHALL be reported as a fault:

- a row the ledger records as landed that the listing does **not** contain — the failure class: the
  photo is invisible to every other member and nothing else will repair it;
- a listed resource the ledger does **not** record — a ledger-durability signal whose cost is one
  idempotent re-upload, never a lost photo. It SHALL be counted and SHALL NOT be reported as a fault.

The fault SHALL be reported at `Error` severity so it reaches crash reporting as an event rather than a
breadcrumb (capability `crash-reporting`). Because every UUID-shaped token is scrubbed before send, the
report SHALL carry **counts** and the resolved upload mechanism rather than identifiers.

The check SHALL be skipped, silently, when no event is configured, and when the configured `eventId`
differs from the `joinedEventId` marker — in the second case the ledger is known-divergent and the
marker-gated reconciliation is pending, so any disagreement is expected rather than informative.

A failed or timed-out listing fetch SHALL be treated as no information: nothing is reported, nothing is
counted, and the next foreground retries. It SHALL NOT be reported as a fault, because it says nothing
about whether the ledger is right.

#### Scenario: A row the backend does not hold is reported

- **WHEN** the ledger records a policy-admitted resource as landed and the per-device listing does not
  contain its key
- **THEN** the check reports at `Error` with the count of such rows and the resolved upload mechanism,
  and the ledger is left unchanged

#### Scenario: The check writes nothing

- **WHEN** the check finds any disagreement in either direction
- **THEN** no ledger row changes state, no row is added or removed, the discovery cursor is untouched, the
  `joinedEventId` marker is untouched, and no upload job is created or cancelled

#### Scenario: Collected residue is not reported

- **WHEN** the backend no longer holds resources whose rows the ledger still records as landed, and the
  membership's current policy does not admit those assets
- **THEN** the check reports nothing — the rows are outside the comparison set

#### Scenario: A listed resource the ledger does not know is counted, not faulted

- **WHEN** the listing contains a resource for which the ledger holds no row
- **THEN** it is counted in its own direction and no fault is reported

#### Scenario: A pending rejoin suppresses the check

- **WHEN** the configured `eventId` differs from the `joinedEventId` marker
- **THEN** the check does not run and reports nothing

#### Scenario: A failed listing fetch reports nothing

- **WHEN** the listing fetch fails or times out
- **THEN** nothing is reported and nothing is counted, and the next foreground retries

#### Scenario: The check runs on both upload tiers

- **WHEN** the app enters the foreground on iOS 18–26.0 or on iOS ≥26.1
- **THEN** the check runs in the app process on both, whichever process holds the ledger writer
