## ADDED Requirements

### Requirement: The ledger row carries the manifest's presentation detail

Each ledger row SHALL carry, in addition to its dedup key and upload state, the fields the device manifest
requires to name a resource: the asset's `creationDate`, and per resource its `role`, `contentType`, and
human `filename`. These fields make the ledger the single durable, deletion-aware record of the device's
in-event resources, so the device manifest can be projected from it (capability `device-manifest`) rather
than maintained in a parallel accumulator that duplicated the same asset set. The dedup key and the
event-provenance `eventId` are unchanged.

#### Scenario: A completed row names its resource fully

- **WHEN** a resource upload completes and its ledger row is COMPLETED
- **THEN** the row carries `creationDate`, `role`, `contentType`, and `filename` sufficient to build the
  resource's device-manifest entry with no additional PhotoKit read
