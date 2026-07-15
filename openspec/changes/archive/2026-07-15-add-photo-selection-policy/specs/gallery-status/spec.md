## MODIFIED Requirements

### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in a new `:domain:gallery` module (package
`app.snapsync.gallery`) whose `size` is a `StateFlow<Int>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived count (never a placeholder
or negative sentinel). The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

The count SHALL be **scoped by the membership's selection policy** (capability `photo-selection-policy`) —
its capture-date cutoff, which is always present, **and** its origin exclusions — the same policy that scopes
the upload cycle's discovery, so the count and the admitted set never diverge. There is no whole-library
count.

Scoping by the cutoff alone is insufficient: an origin-excluded asset that counted toward `N` but was never
uploaded would peg completeness permanently below 100% and hold the joined screen at "pending" forever — the
same failure the cutoff scoping exists to prevent.

#### Scenario: Current size is available synchronously

- **WHEN** a consumer reads `size.value` immediately after obtaining a `GalleryStatusSource`
- **THEN** it receives a real non-negative `Int`, never a placeholder or default sentinel

#### Scenario: Empty library reports zero

- **WHEN** the photo library contains no photos
- **THEN** `size.value` is `0`

#### Scenario: The count is bounded by the same cutoff the cycle uses

- **WHEN** the membership's cutoff is `C` and the library holds assets both before and at-or-after `C`
- **THEN** `size.value` counts only the at-or-after assets — the same bound the upload cycle's discovery
  applies

#### Scenario: The count applies the same origin exclusions the cycle applies

- **WHEN** the library holds a screenshot captured after the cutoff, alongside an admitted camera photo
- **THEN** `size.value` counts only the camera photo, so the joined screen can reach "in sync" once that
  photo uploads

### Requirement: Decision-free raw-asset walk seam

`:domain:gallery` SHALL define a **decision-free** raw-asset walk seam that exposes the PhotoKit library
as raw facts, carrying **no** sync or fan-out decisions. It SHALL surface a `RawAsset` per asset —
carrying the **raw** `localIdentifier` (still containing `/`, un-normalized), the resolved
`creationDate`, the **origin facts** below, and a list of `RawResource` — and a `RawResource` per platform
resource, carrying the **raw** `PHAssetResourceType` value (a stable ABI integer, un-mapped to any role), the
`uniformTypeIdentifier`, the original filename, the **iOS-resolved** MIME content type, and an **opaque
handle** to the underlying `PHAssetResource`. The walk SHALL NOT filter by role, derive any upload key,
normalize the `assetId`, or drop any resource — every original and non-original resource crosses as a
raw fact; the mapping (below) decides what to keep. The MIME content type SHALL be resolved on the iOS
side (via `UTType.preferredMIMEType`, falling back to `application/octet-stream`) and carried as a raw
fact — `commonMain` SHALL NOT reimplement the UTI→MIME table. The seam SHALL offer a **bounded**
whole-library walk and a **bounded** by-local-identifiers walk — each taking a capture-date lower bound as
a parameter; it SHALL NOT offer an unbounded walk of either shape. The bound is a **scope parameter, not a
decision**: what the bound is remains a `commonMain` choice, and the walk merely receives it.

`RawAsset` SHALL additionally carry, as raw facts, the asset's `mediaSubtypes` (the raw bitmask, a stable ABI
integer), `mediaType`, `pixelWidth`, `pixelHeight`, and `hasAdjustments`. These are the inputs the selection
policy's origin rules decide on (capability `photo-selection-policy`), and they cross as **facts, not
decisions** — the walk SHALL NOT itself exclude an asset on any of them. All five are in-memory properties of
the fetched platform asset, so surfacing them SHALL NOT introduce any additional per-asset round-trip.

The bound on the by-identifiers walk is **not** redundant with a platform change feed. A change feed
reports what *changed*, not what is in *scope*: an iCloud sync or a bulk import surfaces thousands of
out-of-scope assets at once. An implementation SHALL therefore reject an out-of-scope asset **before**
reading its resources, using only the asset's own capture date — the resource read is the expensive
operation (one synchronous platform round-trip per asset), the capture date is a plain property. The iOS implementation SHALL be
PhotoKit-backed; `:domain:gallery` SHALL also provide a **settable in-memory** implementation so the
mapping is driven on the JVM and the iOS simulator without PhotoKit. The opaque handle SHALL cross
`commonMain` uninterpreted (a JVM stand-in is valid), exactly as `Resource.data` does.

The PhotoKit implementation SHALL apply the bound to its `PHFetchOptions` predicate so that only in-scope
assets are fetched and the per-asset resource round-trip is issued only for those. It MAY additionally narrow
the predicate by the origin rules it can express — media subtype, and a **bounding box** over `pixelWidth` /
`pixelHeight`. That predicate is an **optimization only**: the pure `commonMain` selection filter downstream
remains authoritative, so the predicate MAY return a superset of the admitted assets but MUST NOT return a
subset. Where the predicate's evaluation could disagree with the authoritative `commonMain` decision at a
boundary, the predicate SHALL be **widened**, never narrowed.

Three constraints on that predicate are **device-verified facts about PhotoKit**, not preferences, and an
implementation SHALL observe them:

- A media-subtype exclusion SHALL be written `NOT ((mediaSubtypes & N) != 0)`. The form
  `(mediaSubtypes & N) == 0` returns **zero rows** — it does not raise — even with the documented plural
  `mediaSubtypes` key. Shipping it would starve the walk of every asset.
- The predicate SHALL NOT contain **arithmetic** (for example `pixelWidth * pixelHeight`); it raises an
  uncatchable `NSException` and aborts the process. A resolution floor SHALL therefore be expressed in the
  predicate only as a **bounding box**, with the authoritative area comparison in `commonMain`.
- The predicate SHALL NOT reference `hasAdjustments`; it is not a supported key and likewise aborts the
  process. The adjustments guard SHALL live in `commonMain`.

#### Scenario: The walk emits raw facts with no decisions

- **WHEN** the walk encounters an asset with both original and non-original resources
- **THEN** it emits every resource as a raw fact, applying no role filter, no key derivation, and no
  `assetId` normalization

#### Scenario: Origin facts cross as facts, not decisions

- **WHEN** the walk encounters a screenshot asset
- **THEN** it emits the asset with its `mediaSubtypes` bitmask intact and does **not** drop it — the
  exclusion decision belongs to the `commonMain` selection filter

#### Scenario: Origin facts add no per-asset round-trip

- **WHEN** the walk reads an asset's `mediaSubtypes`, `mediaType`, `pixelWidth`, `pixelHeight` and
  `hasAdjustments`
- **THEN** it issues no platform round-trip beyond the ones it already makes, because all five are in-memory
  properties of the already-fetched asset

#### Scenario: A predicate that cannot express an exclusion still yields the correct admitted set

- **WHEN** the fetch predicate returns assets the selection policy excludes (because the predicate cannot
  express that rule, or was deliberately widened)
- **THEN** the authoritative `commonMain` filter excludes them, so the admitted set is unchanged
