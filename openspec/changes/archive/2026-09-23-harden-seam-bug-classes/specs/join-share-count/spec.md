## ADDED Requirements

### Requirement: The count runs on the core lane and fails to unavailable

The shareable-count query SHALL reach presentation through the lane-decorated user-query bundle
(capability `module-architecture`, "Queries cross a lane-gated door"), so none of its reads — the
suppressed-id read, the album-exclusion read, the candidate enumeration — runs on the main thread. A
failure of any of those reads SHALL reduce the count to **unavailable**, render no row, and be logged; it
SHALL NOT escape into the composition.

#### Scenario: A preset is tapped

- **WHEN** the member taps a range preset on the join surface
- **THEN** the count's store and photo-library reads run on the core lane, and the main thread performs
  none of them

#### Scenario: A read fails

- **WHEN** the download store throws while the count is computed
- **THEN** the count is unavailable, the surface renders no row, and the failure is logged
