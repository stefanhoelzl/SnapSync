## MODIFIED Requirements

### Requirement: Device-global accumulator with per-event projection

The manifest's entries SHALL derive from a device-global accumulator holding every **admitted**, not-deleted
asset with its manifest detail. "Admitted" means the asset survived the selection policy's **origin
exclusions** (capability `photo-selection-policy`) — it is not a screenshot, screen recording, animated image,
sub-floor-resolution asset, or member of a denylisted album. Each event's manifest SHALL be the date-filtered
projection of that accumulator — the assets whose capture date is at or after **the device's configured
start for that event** (its per-membership capture-date cutoff). Under a
whole-library scope (no cutoff) the projection SHALL be the identity over the accumulator. The
accumulator SHALL remain device-global even when a cutoff is set — it holds every admitted asset,
including those excluded from the current projection by **date** — so that a differing cutoff (a future edit, or a
concurrent membership in another event) can be projected without re-walking the library. An accumulator
entry SHALL be written on **every** discovery of an admitted asset, including an already-uploaded one, so the
accumulator is a rebuildable cache rather than a source of truth; after an App-Group wipe it SHALL
rebuild gradually as discovery re-encounters each present asset.

The two exclusions land on **opposite sides** of the accumulator, and this asymmetry is deliberate. The
**capture-date cutoff is per-membership**, so it SHALL be applied in the per-event *projection* — the
accumulator must retain a pre-cutoff asset because another event's cutoff may admit it. The **origin
exclusions are event-independent** — a screenshot is a screenshot in every event, and no membership will ever
admit one — so they SHALL be applied **before** the accumulator. Pre-filtering by origin therefore costs the
accumulator no per-event flexibility, while pre-filtering by date would.

An origin-excluded asset that reached the accumulator would project into `device.json`, enter the event union,
and be offered to every other member as bytes that were **never uploaded** — because the upload cycle drops it
before the engine. The accumulator's admitted-only contract is what forecloses that.

#### Scenario: Projection equals the accumulator under whole-library scope
- **WHEN** the membership has no cutoff (whole-library scope)
- **THEN** the event's manifest lists exactly the accumulator's not-deleted assets, with no date
  exclusion

#### Scenario: Date-filtered projection per the device's configured cutoff
- **WHEN** the membership has a cutoff and an accumulator asset's capture date precedes it
- **THEN** that asset is excluded from that event's manifest while remaining in the device-global accumulator

#### Scenario: An origin-excluded asset never enters the accumulator
- **WHEN** discovery surfaces a screenshot captured after the membership's cutoff
- **THEN** it is excluded before the accumulator, so it appears in **no** event's manifest and never enters
  the event union — and it does not remain in the accumulator against a future cutoff either

#### Scenario: The manifest never lists an asset whose bytes were not uploaded
- **WHEN** the selection policy excludes an asset from byte upload
- **THEN** that asset appears in no device manifest, so no other member can attempt to download bytes that
  were never uploaded
