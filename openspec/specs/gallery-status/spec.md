# gallery status Specification

## Purpose

The live photo-library size seam: the count of photos currently in the device library, used by the
status projection as the sync total `N`. A platform-backed `StateFlow` (the `GalleryStatusSource`
seam) that `:domain:status` combines with the ledger and permission — so the total reflects photos
the instant they are added, before the background extension records anything. Lives in
`:domain:gallery`; PhotoKit-backed on iOS, a settable in-memory implementation on JVM.

The total is **enumeration-only — no storage LIST** — and it is an *own-device* count: photos downloaded from
other contributors are excluded, because a member's progress is about what they have to share, not about
what has landed in their library. Sourcing `N` from the live library rather than from the ledger is what lets
the screen show a photo as pending the moment it is taken, rather than only once a background cycle has
noticed it.

Decision record: `changes/archive/2026-06-22-gallery-counted-status`.

## Requirements
### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in a new `:domain:gallery` module (package
`app.snapsync.gallery`) whose `size` is a `StateFlow<Int>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived count (never a placeholder
or negative sentinel). The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

The count SHALL be **scoped by the membership's capture-date cutoff** (capability `photo-selection-policy`),
which is always present — the same bound that scopes the upload cycle's discovery, so the count and the
admitted set never diverge. There is no whole-library count.

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

### Requirement: Live re-emission on library change

A `GalleryStatusSource` SHALL re-read and re-emit its `size` when the photo library changes
(`photoLibraryDidChange`), when the app enters the foreground, and when an event is (re)joined — the
same invalidation-ding shape the status sources use. A re-emission carries the freshly read count; the
source MUST NOT emit a count it computed from stale library state.

#### Scenario: New photo bumps the count immediately

- **WHEN** the library gains a photo and a library-change ding fires
- **THEN** `size` emits a value one greater than before, independent of any ledger or extension activity

#### Scenario: Foreground re-reads after a missed change

- **WHEN** the app returns to the foreground after the library changed while it was not running
- **THEN** `size` re-emits the current library count

### Requirement: Platform backing and a settable fake

The iOS implementation SHALL back `size` with a PhotoKit count. The `:domain:gallery` module SHALL
provide a settable in-memory implementation, so the JVM desktop harness and integration tests can drive
any total — including discovery-lag (`N` greater than the ledger's completed count) and overshoot (`N`
less than the ledger's completed count) — without a device.

#### Scenario: Fake count is settable

- **WHEN** a test sets the in-memory gallery source's size to 47
- **THEN** `size.value` is `47` and a collector observes the new value

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` and its in-memory implementation SHALL live in `:domain:gallery`.
`:domain:status` SHALL depend on `:domain:gallery` with **implementation** scope only, so gallery types
never reach `:domain:presentation`'s compile classpath.

#### Scenario: Presentation compiles without the gallery seam

- **WHEN** `:domain:presentation` is compiled
- **THEN** `:domain:gallery` is not on its compile classpath, and no gallery type is reachable from
  presentation code

### Requirement: Library resource enumeration seam

The gallery domain SHALL define, in `:domain:gallery`, a resource-enumeration seam that returns the
library's **in-scope** resources as a list, each carrying `(filename, assetId, version)` — where
`filename` is the upload key (the reinstall-stable identity, `<assetId>-<kind>.<ext>`), `assetId`
groups a photo's resources, and `version` is the content-identity proof (the asset modification
timestamp). The seam SHALL take a **capture-date lower bound** and return only resources of assets at or
after it; it SHALL NOT offer an unbounded enumeration. This is the **single shared derivation** of those
fields: the iOS background-upload producer's full-enumeration path SHALL delegate to it, so the same
`(filename, version)` is computed wherever enumeration happens (the join seed and the producer agree
byte-for-byte). The **app-side status consumer** SHALL **also** consume this seam — to obtain each
asset's **expected** resource set (the set of `filename`s its resources are expected to have) when
computing own-device completeness against the per-device file listing — so the app's notion of "complete"
and the extension's uploaded filenames are derived from one source and agree byte-for-byte. The
enumeration logic itself is unchanged by this additional consumer. The iOS implementation SHALL be
PhotoKit-backed; `:domain:gallery` SHALL also provide a settable in-memory implementation for the JVM
harness and tests. The seam SHALL remain in `:domain:gallery` so its types never reach
`:domain:presentation`'s compile classpath (per "Module placement keeps the seam off presentation").

The enumeration seam SHALL be realized as the composition of the **decision-free raw-asset walk seam**
(above) with a **pure `commonMain` mapping** `RawAsset` → resources. The mapping SHALL be the single
site of the fan-out orchestration: it SHALL normalize the `assetId` `'/'→'_'`, drop every resource whose
raw type maps to no role (`resourceRole` → originals only), derive each kept resource's `filename` via
`uploadKey`, and assemble the per-asset manifest metadata (`creationDate`, original filename, MIME). The
mapping SHALL be pure and platform-free, so this fan-out orchestration — previously exercised only in the
iOS adapter — SHALL be unit-tested on JVM **and** the iOS simulator with a fake raw-asset walk. The iOS
enumeration adapter SHALL shrink to the decision-free walk plus this shared mapping; it SHALL hold no
role filter, key derivation, or normalization of its own.

#### Scenario: Enumeration yields per-resource identity and version
- **WHEN** a consumer enumerates a library with photos that each have one or more resources
- **THEN** it receives one entry per resource, each carrying that resource's `filename`, its photo's
  `assetId`, and the asset's `version`

#### Scenario: The producer and the seed derive identical keys and versions
- **WHEN** the upload producer enumerates a resource and the join seam enumerates the same resource
- **THEN** both yield the same `filename` and the same `version` (one shared derivation)

#### Scenario: The status consumer derives the same expected filenames the producer uploads
- **WHEN** the app-side status consumer enumerates an asset to obtain its expected resource set, and
  the upload producer enumerates the same asset to decide what to upload
- **THEN** both derive the same `filename` set for that asset, so the status consumer's "expected"
  filenames are exactly the keys the producer uploads to its device byte-partition (capability
  `edge-upload-provider`) (byte-for-byte agreement on what "complete" means)

#### Scenario: Enumeration requires a bound and never omits an in-scope asset
- **WHEN** a consumer enumerates with a capture-date lower bound `C`
- **THEN** every resource of every asset at or after `C` is returned, no unbounded enumeration entry point
  exists to call instead, and any asset before `C` that the platform fetch over-returns is removed by the
  consumer's own authoritative cutoff filter

#### Scenario: The in-memory enumeration honours the bound
- **WHEN** a test sets the in-memory enumeration to a resource dated before `C` and one dated at or after `C`,
  and enumerates with bound `C`
- **THEN** only the at-or-after resource is returned, on JVM and on the iOS simulator

#### Scenario: Fake enumeration is settable
- **WHEN** a test sets the in-memory enumeration to a list of resources
- **THEN** enumerating returns exactly those resources, on JVM and on the iOS simulator

#### Scenario: The fan-out mapping is exercised off-device
- **WHEN** a fake raw-asset walk supplies an asset with an original resource, a paired-video resource,
  and an edit-artifact resource, and the enumeration seam maps it
- **THEN** on JVM and the simulator the mapping yields exactly the originals — `<assetId>-primary.<ext>`
  and `<assetId>-live.<ext>` with the `assetId` `'/'→'_'` normalized and the `creationDate`/original-
  filename/MIME metadata attached — and drops the edit artifact, with no PhotoKit involved

### Requirement: Upload-key to assetId round-trip parser

`:domain:gallery` SHALL own a **single** `assetIdFromUploadKey` parser — the exact inverse of its
`uploadKey` derivation — that recovers a resource's `assetId` from a bare upload key
(`<assetId>-<role>.<ext>`). It SHALL be the **only** implementation of that parse: both the
extension-side upload-job reconstruction (`ios-photokit-upload`, "Completion and retry adjudication")
and the re-join reconciler (`event-rejoin-reconciliation`) SHALL call this one function, replacing any
private per-module copy. Because the parse is now load-bearing at the record path (a mis-parse writes a
wrong or empty `assetId`), the round-trip SHALL be pinned by a test: for every key `uploadKey` produces,
`assetIdFromUploadKey` SHALL recover the original `assetId`. The parser SHALL remain in `:domain:gallery`
so its types never reach `:domain:presentation`'s compile classpath (per "Module placement keeps the
seam off presentation").

#### Scenario: assetId round-trips through the upload key

- **WHEN** `uploadKey` derives a key for a resource with a given `assetId` and role
- **THEN** `assetIdFromUploadKey` applied to that key returns the original `assetId`, for assetIds with
  and without embedded `-`, on JVM and on the iOS simulator

#### Scenario: Both consumers use the one parser

- **WHEN** the upload-job reconstruction and the re-join reconciler each recover an `assetId` from a key
- **THEN** both call `:domain:gallery`'s `assetIdFromUploadKey`, with no private duplicate remaining in
  `:capability:membership` or the upload cycle

### Requirement: Decision-free raw-asset walk seam

`:domain:gallery` SHALL define a **decision-free** raw-asset walk seam that exposes the PhotoKit library
as raw facts, carrying **no** sync or fan-out decisions. It SHALL surface a `RawAsset` per asset —
carrying the **raw** `localIdentifier` (still containing `/`, un-normalized), the resolved
`creationDate`, and a list of `RawResource` — and a `RawResource` per platform resource, carrying the
**raw** `PHAssetResourceType` value (a stable ABI integer, un-mapped to any role), the
`uniformTypeIdentifier`, the original filename, the **iOS-resolved** MIME content type, and an **opaque
handle** to the underlying `PHAssetResource`. The walk SHALL NOT filter by role, derive any upload key,
normalize the `assetId`, or drop any resource — every original and non-original resource crosses as a
raw fact; the mapping (below) decides what to keep. The MIME content type SHALL be resolved on the iOS
side (via `UTType.preferredMIMEType`, falling back to `application/octet-stream`) and carried as a raw
fact — `commonMain` SHALL NOT reimplement the UTI→MIME table. The seam SHALL offer a **bounded**
whole-library walk and a **bounded** by-local-identifiers walk — each taking a capture-date lower bound as
a parameter; it SHALL NOT offer an unbounded walk of either shape. The bound is a **scope parameter, not a
decision**: what the bound is remains a `commonMain` choice, and the walk merely receives it.

The bound on the by-identifiers walk is **not** redundant with a platform change feed. A change feed
reports what *changed*, not what is in *scope*: an iCloud sync or a bulk import surfaces thousands of
out-of-scope assets at once. An implementation SHALL therefore reject an out-of-scope asset **before**
reading its resources, using only the asset's own capture date — the resource read is the expensive
operation (one synchronous platform round-trip per asset), the capture date is a plain property. The iOS implementation SHALL be
PhotoKit-backed; `:domain:gallery` SHALL also provide a **settable in-memory** implementation so the
mapping is driven on the JVM and the iOS simulator without PhotoKit. The opaque handle SHALL cross
`commonMain` uninterpreted (a JVM stand-in is valid), exactly as `Resource.data` does.

The PhotoKit implementation SHALL apply the bound to its `PHFetchOptions` predicate so that only in-scope
assets are fetched and the per-asset resource round-trip is issued only for those. That predicate is an
**optimization only**: the pure `commonMain` cutoff filter downstream remains authoritative, so the
predicate MAY return a superset of the in-scope assets but MUST NOT return a subset. Where the predicate's
date evaluation could disagree with the authoritative lexicographic string compare at the boundary, the
predicate SHALL be **widened**, never narrowed.

#### Scenario: The walk emits raw facts with no decisions
- **WHEN** the raw-asset walk enumerates an asset with original and non-original (edit-artifact/proxy)
  resources
- **THEN** it emits a `RawAsset` carrying the raw un-normalized `localIdentifier` and one `RawResource`
  per platform resource — including the non-original ones — each carrying the raw `PHAssetResourceType`
  value, UTI, iOS-resolved MIME, original filename, and opaque handle, with no role filtering, key
  derivation, or normalization applied

#### Scenario: MIME is a raw fact resolved iOS-side
- **WHEN** a `RawResource` is produced for a resource with a given `uniformTypeIdentifier`
- **THEN** its `mimeContentType` is the iOS-resolved `UTType.preferredMIMEType` (or
  `application/octet-stream` when unresolved), and `commonMain` never computes it from the UTI

#### Scenario: The bounded walk takes a lower bound and no unbounded walk exists
- **WHEN** a consumer walks the library, by capture date or by local identifiers
- **THEN** it must supply a capture-date lower bound, and no unbounded walk of either shape is available
  on the seam

#### Scenario: A changed but out-of-scope asset costs no resource round-trip
- **WHEN** the by-identifiers walk is given an asset whose `creationDate` precedes the bound (a changed
  asset the platform's change feed surfaced, e.g. from an iCloud sync)
- **THEN** the asset is skipped without its resources being read, and it is absent from the result

#### Scenario: The platform predicate may over-return but never under-return
- **WHEN** the PhotoKit walk's fetch predicate is evaluated for a bound `C`
- **THEN** every asset whose `creationDate` is at or after `C` is present in the fetch result, even if
  assets before `C` are also present, and the downstream pure filter removes the latter

#### Scenario: The walk is settable for tests
- **WHEN** a test sets the in-memory raw-asset walk to a list of `RawAsset`s
- **THEN** the bounded and by-identifiers walks return exactly those, on JVM and on the iOS simulator

