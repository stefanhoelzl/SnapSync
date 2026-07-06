## MODIFIED Requirements

### Requirement: Device-global accumulator with per-event projection

The manifest's entries SHALL derive from a device-global accumulator holding every discovered,
not-deleted asset with its manifest detail. Each event's manifest SHALL be the date-filtered
projection of that accumulator — the assets whose capture date is at or after **the device's configured
start for that event** (its per-membership capture-date cutoff, capability `photo-date-cutoff`). Under a
whole-library scope (no cutoff) the projection SHALL be the identity over the accumulator. The
accumulator SHALL remain device-global even when a cutoff is set — it holds every discovered asset,
including those excluded from the current projection — so that a differing cutoff (a future edit, or a
concurrent membership in another event) can be projected without re-walking the library. An accumulator
entry SHALL be written on **every** discovery of an asset, including an already-uploaded one, so the
accumulator is a rebuildable cache rather than a source of truth; after an App-Group wipe it SHALL
rebuild gradually as discovery re-encounters each present asset.

#### Scenario: Projection equals the accumulator under whole-library scope
- **WHEN** the membership has no cutoff (whole-library scope)
- **THEN** the event's manifest lists exactly the accumulator's not-deleted assets, with no date
  exclusion

#### Scenario: Date-filtered projection per the device's configured cutoff
- **WHEN** the membership has a cutoff and an accumulator asset's capture date precedes it
- **THEN** that asset is excluded from that event's manifest while remaining in the device-global accumulator

#### Scenario: The accumulator stays device-global under a cutoff
- **WHEN** a cutoff excludes some assets from the manifest projection
- **THEN** those assets are still retained in the accumulator, so a lower cutoff could re-project them without a re-walk

#### Scenario: Entry written on every discovery, accumulator rebuilds gradually
- **WHEN** an asset is discovered even though it is already uploaded
- **THEN** its accumulator entry is written
- **AND** after an App-Group wipe the accumulator rebuilds gradually as discovery re-encounters each
  present asset
