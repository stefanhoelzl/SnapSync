## ADDED Requirements

### Requirement: A created version record carries the copyright

When the release workflow **creates** an App Store version record, it SHALL set the record's `copyright`
attribute in the create request, so the record is never born without one. The copyright SHALL be a
committed constant of the form `YYYY Name`, where `YYYY` is the year of **first publication** — it SHALL
NOT track the current calendar year.

The workflow SHALL NOT modify the copyright of a version record that **already exists**: an existing
record is reused as-is (capability requirement "The build is attached to its App Store version record"),
so a value set by hand in the ASC web console is left intact. Copyright is therefore enforced at birth
rather than reconciled per run — it is a version **attribute**, so it cannot ride the declarative
per-locale metadata push (whose schema is closed to it), and the App Store Connect API does not expose it
for read-back through the metadata tool, making per-run drift detection impossible.

#### Scenario: A created record is born with the copyright
- **WHEN** the release workflow creates the App Store version record for a `vX.Y` tag because none exists
- **THEN** the create request sets the record's `copyright`, and the resulting record carries it

#### Scenario: An existing record's copyright is not touched
- **WHEN** the release workflow finds an App Store version record that already exists for the store version
- **THEN** it reuses that record and makes no change to its `copyright`

#### Scenario: The copyright year does not track the calendar
- **WHEN** a release runs in a calendar year later than the app's first publication
- **THEN** the copyright applied to a newly created record still carries the year of first publication
