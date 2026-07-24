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

The snapshot SHALL nonetheless continue to be read **eagerly, with resources**, at those sanctioned
points. The spike licenses a lazy per-asset read where the asset reference is still held; it does not
license one across the snapshot cell, because reaching those assets again later would mean either holding
platform references for an unbounded period — storm-safety resting on an invariant no type expresses — or
re-fetching by local identifier, which is the measured storm itself. The eager read is what keeps every
library **fetch** in-flow, and a limited selection is hand-picked and small, so the deferral would save
almost nothing for that risk. The lazy path belongs to the *walking* sources, where the reference never
leaves the call.

#### Scenario: The admitted set under LIMITED is the filtered selection

- **WHEN** permission is `LIMITED` and the admission resolves the set
- **THEN** it filters the fed selection snapshot by the same policy it would apply to a walk, and issues no
  autonomous library fetch to do so

#### Scenario: The snapshot's resources are already in hand

- **WHEN** a consumer under `LIMITED` needs an admitted asset's resources
- **THEN** they are already held from the sanctioned read — nothing is fetched again, and in particular no
  fetch by local identifier is issued outside the sanctioned points

#### Scenario: No autonomous fetch is issued under LIMITED

- **WHEN** any consumer resolves the admitted set under `LIMITED`
- **THEN** no library fetch/query is issued outside the cold-launch baseline and the observer emissions —
  the selection always arrives as a fed snapshot
