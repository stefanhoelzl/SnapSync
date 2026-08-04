# join-share-count Specification

## Purpose

**How many of my photos will this share?** The join surface lets a member scope their contribution with a
capture-date cutoff, but until this capability it said nothing about the consequence. This is the
pre-commit **shareable-count preview** that answers the question live, as the member tunes the cutoff on
the join and reconfigure surfaces: `XX photos from your gallery will be shared`. (A switch reaches the
join surface after its leave, so it inherits the count there; the confirmation that precedes the leave has
no chosen range to count.)

It exists because of the danger the mission names: a default inherited from the app's one-way-backup
origins turns "back up everything of mine" into "upload a guest's whole camera roll to a stranger's
event". A visible count turns the cutoff from an abstract date into a number the member sees **before** they
commit — the reassurance that they are sharing an event's worth of photos, not their whole library.

The count means **photos shared to this event** — the own-gallery set the selection policy admits for the
candidate cutoff — **not** bytes physically transferred. That choice is deliberate: a member never cares how
photos arrive (the mission), only which of their photos are shared, so the count is computed **purely
locally with no backend call**, and a returning member whose bytes are already stored still sees every
photo that will appear in the event. It is the **same policy** the byte upload, the device manifest, and
the status total `N` apply (capability `photo-selection-policy`), evaluated against the surface's
**candidate** cutoff rather than a persisted one — so the number a member sees equals the set that would
upload and list, one universe rather than a looser preview rule.

Two platform facts shape the implementation. First, the count needs **no per-asset resource read**: the
expensive `assetResourcesForAsset` XPC round-trip builds upload keys, which a count does not need, so the
preview reads only cheap in-memory `PHAsset` facts and recomputes as the member sweeps the cutoff without
re-paying that cost. Second, under a **limited** grant it never issues a fresh library read (capability
`limited-photo-access` forbids autonomous off-flow reads): it re-filters the already-held selection
snapshot in memory, which is exactly the membership's own-photo scope. Without a usable grant the count is
unavailable and the surface renders no row.

Decision record: `changes/archive/2026-07-22-show-join-share-count`.

## Requirements
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
pixel dimensions, and `hasAdjustments` — bounded by a policy-derived fetch predicate. The denylisted-album
exclusion (whose cost is proportional to albums, not assets — capability `photo-selection-policy`) SHALL
be resolved **once per surface** and reused across every recompute, since album membership does not vary
with the candidate cutoff. The computation SHALL run off the main thread so it never risks the scene-
update watchdog, however far back the candidate cutoff reaches.

This cheapness SHALL NOT cost the count its accuracy. Every selection rule is decidable on asset facts
alone (capability `photo-selection-policy`), so the count that skips the resource read is the **exact**
admitted-set size, not an approximation of it: the preview and the own-device status total report the same
number for the same bounds over the same library.

That was previously not true. While one rule — the animated-image exclusion — needed a resource's MIME
content type to decide, the facts-only preview could not see it and admitted such an asset on doubt, while
the eager status walk saw it and excluded it. The two consumers of "the admitted set" therefore disagreed
by exactly the GIFs in scope. Removing that rule is what makes cheap and exact the same answer.

#### Scenario: Counting performs no per-asset resource read

- **WHEN** the shareable-count is computed over a library of admitted photos
- **THEN** it reads only cheap `PHAsset` properties and never calls `assetResourcesForAsset`, so its cost
  is a bounded fetch plus in-memory property reads, not one XPC round-trip per asset

#### Scenario: The cheap count is the exact count

- **WHEN** the preview counts a candidate range and the own-device status total counts the same committed
  range over the same library
- **THEN** the two numbers are equal — the preview admits nothing the total excludes, and the cheapness of
  the path costs it no accuracy

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

