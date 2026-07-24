## MODIFIED Requirements

### Requirement: Mutable full-state projection

The device manifest SHALL be projected from the upload **ledger** (capability `sync-ledger`), not from a
separate accumulator. For a given event the manifest SHALL list exactly the ledger's **COMPLETED** rows
whose asset falls within the current membership's admitted capture-date range (capability
`photo-selection-policy`) — a full-state document listing only genuinely-uploaded resources. The parallel
device-global accumulator (which maintained the same deletion-aware asset set with different columns) is
removed. Deletion-awareness comes from the ledger's own pruning (a deleted asset's rows are dropped).

Because the manifest now lists only COMPLETED resources, the event union's byte-presence check (capability
`bunny-list-endpoint`) is no longer the mechanism that hides not-yet-uploaded assets; it becomes
defense-in-depth against a COMPLETED-but-absent byte.

#### Scenario: The manifest lists completed rows in the event window

- **WHEN** the manifest is produced for an event
- **THEN** it lists exactly the device's COMPLETED ledger resources whose asset is within the membership's
  admitted range — no discovered-but-unuploaded asset, and nothing outside the range

#### Scenario: A deleted asset drops from the manifest

- **WHEN** an asset is deleted locally and its ledger rows are pruned
- **THEN** it no longer appears in the projected manifest
