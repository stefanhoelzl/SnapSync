## ADDED Requirements

### Requirement: The limited selection is a facts-only candidate source for the admitted set

Under `LIMITED`, the user's hand-picked selection SHALL be presented to the admission (capability
`photo-selection-policy`) as one **candidate source** — pre-filled with the current selection — so the
same single admission runs over it exactly as over a full-library walk under `GRANTED`. The admission and
the `EventPhotoSet` abstraction SHALL be permission-oblivious: the mode difference is one source impl, not
a branch in the policy or its consumers. No native fetch narrowing applies under `LIMITED` (there is no
walk to narrow); the authoritative in-memory admission filters the captured selection.

The sanctioned-read discipline SHALL be scoped to **library fetches**, not to every PhotoKit call, and
SHALL live entirely in how the source is **constructed and fed**:

- A library **fetch/query** (`PHAsset.fetchAssets…`) SHALL NOT be issued autonomously. The selection is
  captured only at the cold-launch baseline and at photo-selection-change observer emissions, and the
  source is **fed** that snapshot — never pulled. This is the measured storm: off-flow fetches queue
  limited-access alerts that survive process death, and
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` does **not** reliably suppress them (decision record
  `changes/archive/2026-07-20-accept-limited-photo-access`).
- A per-asset **resource read** (`assetResourcesForAsset`) of an already-selected asset MAY be issued
  off-flow. This was measured storm-free on device (SE2, iOS 26.5.2, `.limited`, alert-suppression on):
  six off-flow bursts over already-held baseline refs produced zero alerts, during the bursts and on the
  bare home screen after a `SIGKILL`.

Consequently the LIMITED snapshot SHALL carry **facts only** (what the admission decides on), and each
admitted asset's resources SHALL be read **lazily**, through the same `Asset.resources()` seam `GRANTED`
uses — one path for both grants, and no resource read paid for an asset the policy excludes.

#### Scenario: The admitted set under LIMITED is the filtered selection

- **WHEN** permission is `LIMITED` and the admission resolves the set
- **THEN** it filters the fed selection snapshot by the same policy it would apply to a walk, and issues no
  autonomous library fetch to do so

#### Scenario: Resources are read lazily, only for admitted assets

- **WHEN** a consumer under `LIMITED` needs an admitted asset's resources
- **THEN** they are read per-asset on demand through the same seam `GRANTED` uses — the snapshot itself
  carries facts only, and an asset the policy excludes never costs a resource read

#### Scenario: No autonomous fetch is issued under LIMITED

- **WHEN** any consumer resolves the admitted set under `LIMITED`
- **THEN** no library fetch/query is issued outside the cold-launch baseline and the observer emissions —
  the selection always arrives as a fed snapshot
