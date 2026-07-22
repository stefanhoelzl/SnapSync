## ADDED Requirements

### Requirement: A pre-commit count of the photos that would be shared

The system SHALL expose a **shareable-count** read-model that answers, for a **candidate** cutoff and
participation direction not yet committed, **how many of the device's own photos would be shared to the
event**. The count SHALL be the size of the set the selection policy admits (capability
`photo-selection-policy`) — the direction includes upload, the photo's `creationDate` is at or after the
candidate cutoff, and no origin exclusion applies — so that the number a member sees equals the set that
would upload and list. The read-model SHALL be a **query** parameterised by the candidate cutoff and
direction, not a passive reduction of committed state, because the candidate cutoff is uncommitted UI
choice on the join surface.

The count means **photos shared to this event**, NOT bytes physically transferred. It SHALL therefore be
computed **without any network call** — it SHALL NOT consult the backend's already-stored device files —
so a member whose bytes were already stored from a previous event still sees every photo that will appear
in this event. This is deliberate: a member never cares how photos arrive (the mission), only which of
their photos are shared.

#### Scenario: The count equals the policy-admitted set for the candidate cutoff
- **WHEN** the shareable-count query runs for a candidate cutoff `C` and a direction that includes upload
- **THEN** it returns the number of the device's own assets whose `creationDate >= C` and which no origin
  exclusion rejects — the same set the upload cycle would admit for that cutoff

#### Scenario: A non-contributing direction counts zero
- **WHEN** the candidate direction excludes upload (Share off / `DownloadOnly`)
- **THEN** the count is `0` and no library enumeration is performed

#### Scenario: The count ignores already-stored bytes
- **WHEN** the device has previously uploaded photos (their bytes already stored) that are also in scope
  for the candidate cutoff
- **THEN** those photos are still counted, and no request is made to the backend's device-files listing

### Requirement: The count is computed cheaply, without a per-asset resource read

The shareable-count SHALL be computed on a path that does **not** perform the per-asset PhotoKit resource
read (`PHAssetResource.assetResourcesForAsset`), because that synchronous XPC round-trip (~110 ms per
asset) is needed only to build upload keys, not to decide admission. The count SHALL instead evaluate the
policy over **cheap in-memory `PHAsset` properties** — `creationDate`, `mediaSubtypes`, `mediaType`,
pixel dimensions, and `hasAdjustments` — bounded by a cutoff-scoped fetch predicate. The denylisted-album
exclusion (whose cost is proportional to albums, not assets — capability `photo-selection-policy`) SHALL
be resolved **once per surface** and reused across every recompute, since album membership does not vary
with the candidate cutoff. The computation SHALL run off the main thread so it never risks the scene-
update watchdog, however far back the candidate cutoff reaches.

#### Scenario: Counting performs no per-asset resource read
- **WHEN** the shareable-count is computed over a library of admitted photos
- **THEN** it reads only cheap `PHAsset` properties and never calls `assetResourcesForAsset`, so its cost
  is a bounded fetch plus in-memory property reads, not one XPC round-trip per asset

#### Scenario: The album denylist is resolved once and reused
- **WHEN** the candidate cutoff changes several times on one surface
- **THEN** the denylisted-album membership is looked up once and reused for every recompute, not re-read
  per change

### Requirement: The count honours the photo-access grant, never forcing an off-flow read

The shareable-count SHALL be computed according to the current photo-access grant (capability
`limited-photo-access`, `permission-gate`):

- under **GRANTED**, from a cutoff-bounded fetch over the whole library (the cheap-property path above);
- under **LIMITED**, by re-filtering the **already-held selection snapshot** in memory against the
  candidate cutoff and the policy — which is exactly the membership's own-photo scope under a limited
  grant — and it SHALL NOT trigger any fresh `PHAsset` read, honouring the limited-access prohibition on
  autonomous off-flow reads;
- under **DENIED** or an unresolved **NOT_DETERMINED** grant, the count SHALL be **unavailable** and the
  surface SHALL render no count (see capabilities `join-event`, `reconfigure-membership`).

#### Scenario: Under a limited grant the count re-filters the held selection with no new read
- **WHEN** the grant is `LIMITED` and the candidate cutoff changes
- **THEN** the count is recomputed by filtering the already-pushed selection snapshot in memory, and no
  fresh photo-library read is issued

#### Scenario: Without a usable grant the count is unavailable
- **WHEN** photo access is `DENIED`, or still unresolved `NOT_DETERMINED`
- **THEN** the shareable-count is unavailable, and no library read is attempted
