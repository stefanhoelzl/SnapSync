# harness world model Delta Specification

## ADDED Requirements

### Requirement: Faithful leave composition helper

The world SHALL provide a `leave()` composition helper that runs the **real** leave edge —
`DownloadController.onLeaveOrSwitch()` (cancel in-flight transfers, prune non-terminal download rows)
followed by clearing the config cell and the joined-event marker — while **retaining** imported
foreign photos, deposited objects, the gallery, and the ledger. It SHALL NOT be modelled by rebuilding
the world (which would forge the outcome and wrongly discard imported photos). Because clearing the
config cell is reactive, the listing-backed status projection SHALL leave the joined layer without any
world rebuild, and re-provisioning the same event afterwards SHALL still find the previously imported
foreign assets suppressed (real cross-event dedup). This mirrors the extension/app leave use-case in
the same "real stack over the world's fakes" posture as the other composition helpers.

#### Scenario: Leave keeps imported photos and clears the join

- **WHEN** a foreign asset has been downloaded and imported, and `leave()` is then invoked
- **THEN** the real `onLeaveOrSwitch()` runs, the config cell and joined-event marker are cleared, and
  the imported asset remains enumerable in the gallery (it is not discarded)

#### Scenario: Re-provisioning after leave still suppresses the import

- **WHEN** the same event is re-provisioned after `leave()`
- **THEN** the previously imported foreign asset is still in `suppressedLocalIds()` and the own-device
  cycle does not re-upload it
