# gallery status Specification

## Purpose

The live photo-library size seam: the count of photos currently in the device library, used by the
status projection as the sync total `N`. A platform-backed `StateFlow` (the `GalleryStatusSource`
seam) that the `feature/status` projection combines with the ledger and permission — so the total
reflects photos the instant they are added, before the background extension records anything. The
seam lives in `:domain`'s `ports/` zone; PhotoKit-backed on iOS (`:adapter:ios:ext-safe`), with the
honest in-memory implementation in `:adapter:generic:fake` (re-homed at migration step 10).

The total is **enumeration-only — no storage LIST** — and it is an *own-device* count: photos downloaded from
other contributors are excluded, because a member's progress is about what they have to share, not about
what has landed in their library. Sourcing `N` from the live library rather than from the ledger is what lets
the screen show a photo as pending the moment it is taken, rather than only once a background cycle has
noticed it.

Decision record: `changes/archive/2026-06-22-gallery-counted-status`.
## Requirements
### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in `:domain`'s `ports/` zone (seated by
migration step 3a; born in the since-deleted `:domain:gallery` module) whose `size` is a `StateFlow<Int>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived count (never a placeholder
or negative sentinel). The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

The count SHALL be **scoped by the membership's selection policy** (capability `photo-selection-policy`),
carried as a `SelectionPolicy` — the already-decided rule list, covering the capture-date bounds, the origin
exclusions, **and** the participation direction. It is the same value, with the same rules, that scopes the
upload cycle's discovery, so the count and the admitted set never diverge. There is no whole-library count.

Scoping by the capture-date bounds alone is insufficient: an origin-excluded asset that counted toward `N`
but was never uploaded would peg completeness permanently below 100% and hold the joined screen at "pending"
forever — the same failure the date scoping exists to prevent. Scoping by date and origin alone is
insufficient for the same reason: a membership that contributes nothing uploads nothing, so any non-zero count
pegs the screen below 100% forever.

`SelectionPolicy` SHALL be a **required** parameter of the count, with no default and no "unscoped" value.
This is a privacy requirement, not an ergonomic one: no value and no absent-argument fallback may scope the
count to the whole library. A default is prohibited in both polarities — a permissive one admitting every
capture date spans the entire library from the beginning of time, and a fail-closed one — a policy that
admits nothing — makes a contributing member's screen read "In sync" over a count of nothing. A non-contributing membership — one whose rule list carries the deny-everything rule —
counts `0` **without paying a per-asset read**, and a device with no membership has no scope to count at
all — the composition root simply does not refresh, and `N` remains at its seeded `0`.

The permissive polarity is closed at the **one derivation** that turns a membership into a policy
(capability `photo-selection-policy`): it always emits the capture-date lower-bound rule, because the
persisted lower bound is non-null. The count SHALL NOT read a bound off the policy at all — it asks
`admits`, and takes any bound it needs for a platform query from the membership it already holds. There is
therefore no accessor that could report an absent floor for either reason.

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

#### Scenario: A non-contributing membership counts zero without a per-asset read

- **WHEN** the count is refreshed for a non-contributing membership whose library holds photos
- **THEN** `size.value` is `0`, and the platform query is narrowed to match no asset, so no per-asset
  round-trip is paid to reach it

#### Scenario: The count never reads a floor off the policy

- **WHEN** the count needs a capture-date bound to scope a platform query
- **THEN** it takes that bound from the membership, and the policy offers no accessor whose absent value
  could mean either "contributes nothing" or "has no floor"

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

The iOS implementation SHALL back `size` with a PhotoKit count. `:adapter:generic:fake` SHALL provide the
honest in-memory implementation (`InMemoryGalleryStatusSource`, re-homed from the deleted
`:domain:gallery` at migration step 10), whose count is a **constructor-injected state cell** —
whoever owns the cell (a test, a `:test:world` wrapper) drives any total, including discovery-lag
(`N` greater than the ledger's completed count) and overshoot (`N` less than the ledger's completed
count), without a device; the fake itself exposes only the port (the fake-honesty gate,
`architecture-guards`).

#### Scenario: Fake count is driven through the owned cell

- **WHEN** a test constructs the in-memory gallery source over its own cell and writes 47 to it
- **THEN** `size.value` is `47` and a collector observes the new value

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` SHALL live in `:domain`'s `ports/` zone (seated by migration step 3a) and its
honest in-memory implementation in `:adapter:generic:fake` (re-homed at migration step 10).
`:ui:presentation` (re-homed from `:domain:presentation` at migration step 9) SHALL NOT depend on
`:adapter:generic:fake`, so no fake gallery type is reachable from presentation code; presentation consumes
gallery-derived counts only through the `feature/status` read-models.

#### Scenario: Presentation compiles without the gallery fakes

- **WHEN** `:ui:presentation` is compiled
- **THEN** `:adapter:generic:fake` is not on its compile classpath, and no in-memory gallery type is
  reachable from presentation code

### Requirement: Library resource enumeration seam

The gallery domain SHALL define, in `:domain`'s `ports/` zone, a **single** library-read seam
(`CandidateSource`) that takes the membership's **selection policy** and returns the library's candidate
assets. It SHALL be the only seam through which the photo library is read for admission; there SHALL NOT be
a second enumeration port layered over or beneath it, and no read seam SHALL accept a flattened capture-date
bound in place of the policy.

Each candidate SHALL carry the asset's **neutral facts** (capability `photo-selection-policy` — the inputs
every rule decides on) and a means of obtaining that asset's **resources on demand**. Facts SHALL be
readable without a per-asset platform round-trip; resources SHALL cost one. Because admission is decidable
on facts alone, a consumer that needs only a count or the admitted asset set SHALL issue **no** resource
read, and a consumer that needs resources SHALL pay only for assets already admitted.

A resource SHALL carry `(filename, assetId, contentType, metadata)` — where `filename` is the upload key
(the reinstall-stable identity, `<assetId>-<kind>.<ext>`) and `assetId` groups a photo's resources. There is
**no** `version`: existence under the upload key is the proof of upload, so nothing compares content versions
and the ledger keeps no timestamp (capability `sync-ledger`).

This is the **single shared derivation** of those fields: the iOS background-upload producer's
full-enumeration path SHALL delegate to the same mapping, so the same `filename` is computed wherever
enumeration happens (the join seed and the producer agree byte-for-byte). The **app-side status consumer**
SHALL **also** consume this seam — but only to count the device's admitted assets, which is the status total
`N` (capability `sync-status`). It SHALL NOT derive an expected-filename set and SHALL NOT read the
per-device listing: own-device completeness is ledger-backed, and the status path issues no storage LIST.
What the shared seam guarantees is that the total counts exactly the assets the cycle would upload — so the
screen can reach 100% — not that two derivations of "complete" agree. Presentation SHALL keep consuming
counts only through the `feature/status` read-models, never the read seam directly (per "Module placement
keeps the seam off presentation").

The resource fan-out SHALL remain a **pure `commonMain` mapping**, the single site of that orchestration: it
SHALL normalize the `assetId` `'/'→'_'`, drop every resource whose raw type maps to no role (`resourceRole`
→ originals only), derive each kept resource's `filename` via `uploadKey`, and assemble the per-asset
manifest metadata. It SHALL be pure and platform-free and SHALL be unit-tested on JVM **and** the iOS
simulator; the platform adapter SHALL call it rather than reimplement any part of it, and SHALL hold no role
filter, key derivation, or normalization of its own. The MIME content type SHALL be resolved on the iOS side
(via `UTType.preferredMIMEType`, falling back to `application/octet-stream`) and carried as a raw fact —
`commonMain` SHALL NOT reimplement the UTI→MIME table.

The iOS implementation SHALL be PhotoKit-backed; `:adapter:generic:fake` SHALL provide the honest in-memory
implementation (state cell constructor-injected) so the mapping is driven on the JVM and the iOS simulator
without PhotoKit. An opaque platform handle SHALL cross `commonMain` uninterpreted (a JVM stand-in is
valid), exactly as `Resource.data` does.

The PhotoKit implementation SHALL derive its `PHFetchOptions` predicate by **translating the policy's rules**
(capability `photo-selection-policy`), not from a caller-supplied bound. The predicate is an **optimization
only**: the authoritative in-memory admission runs over whatever the fetch returns, so the predicate MAY
return a superset of the admitted assets but MUST NOT return a subset. Where the predicate's evaluation
could disagree with the authoritative decision at a boundary, the predicate SHALL be **widened**, never
narrowed. The capture-date lower bound SHALL always be pushed into the predicate, because an unbounded walk
is watchdog-killed before the authoritative filter runs.

Three constraints on that predicate are **device-verified facts about PhotoKit**, not preferences, and an
implementation SHALL observe them:

- A media-subtype exclusion SHALL be written `NOT ((mediaSubtypes & N) != 0)`. The form
  `(mediaSubtypes & N) == 0` returns **zero rows** — it does not raise — even with the documented plural
  `mediaSubtypes` key. Shipping it would starve the walk of every asset.
- The predicate SHALL NOT contain **arithmetic** (for example `pixelWidth * pixelHeight`); it raises an
  uncatchable `NSException` and aborts the process. A resolution floor SHALL therefore be expressed in the
  predicate only as a **bounding box**, with the authoritative area comparison in `commonMain`.
- The predicate SHALL NOT reference `hasAdjustments`; it is not a supported key and likewise aborts the
  process.

A change feed reports what *changed*, not what is in *scope*: an iCloud sync or a bulk import surfaces
thousands of out-of-scope assets at once. An implementation reading a change feed SHALL therefore reject an
out-of-scope asset **before** reading its resources, using only the asset's own facts.

#### Scenario: One seam, taking the policy

- **WHEN** any consumer reads the library for admission
- **THEN** it calls the single candidate source with the membership's selection policy — no consumer
  flattens the policy to a capture-date bound, and no second enumeration port exists

#### Scenario: A count pays no resource read

- **WHEN** the status total or the join preview resolves a count
- **THEN** the candidate source returns facts-carrying candidates and no per-asset resource round-trip is
  issued

#### Scenario: Resources are fetched only for admitted assets

- **WHEN** a consumer needs the resources of the admitted set
- **THEN** the per-asset resource read is issued only for assets the policy admitted, never for one it
  excluded

#### Scenario: Enumeration yields per-resource identity

- **WHEN** a consumer resolves the resources of a library's admitted photos
- **THEN** each resource carries the upload key as its `filename` and the normalized `assetId` grouping its
  photo's resources, derived by the shared pure mapping

#### Scenario: The platform predicate cannot change the admitted set

- **WHEN** the platform translates the policy's rules into a native predicate and the predicate returns a
  superset of the admitted assets
- **THEN** the authoritative in-memory admission still excludes every non-admitted asset, so the result is
  identical to an unnarrowed fetch

### Requirement: Upload-key to assetId round-trip parser

`:domain`'s `model/` zone (seated by migration step 3a) SHALL own a **single** `assetIdFromUploadKey` parser — the exact inverse of its
`uploadKey` derivation — that recovers a resource's `assetId` from a bare upload key
(`<assetId>-<role>.<ext>`). It SHALL be the **only** implementation of that parse: both the
extension-side upload-job reconstruction (`ios-photokit-upload`, "Completion and retry adjudication")
and the re-join reconciler (`event-rejoin-reconciliation`) SHALL call this one function, replacing any
private per-module copy. Because the parse is now load-bearing at the record path (a mis-parse writes a
wrong or empty `assetId`), the round-trip SHALL be pinned by a test: for every key `uploadKey` produces,
`assetIdFromUploadKey` SHALL recover the original `assetId`. The parser SHALL remain in `model/`,
the one shared derivation both consumers import (per "Module placement keeps the
seam off presentation").

#### Scenario: assetId round-trips through the upload key

- **WHEN** `uploadKey` derives a key for a resource with a given `assetId` and role
- **THEN** `assetIdFromUploadKey` applied to that key returns the original `assetId`, for assetIds with
  and without embedded `-`, on JVM and on the iOS simulator

#### Scenario: Both consumers use the one parser

- **WHEN** the upload-job reconstruction and the re-join reconciler each recover an `assetId` from a key
- **THEN** both call `model/`'s `assetIdFromUploadKey`, with no private duplicate remaining in
  the reconciler or the upload cycle

### Requirement: The domain reads neutral asset facts, not platform ABI

The platform library walk SHALL map each `PHAsset` to a **neutral** `AssetFacts` value carrying only
platform-independent facts the policy decides on — the normalized `assetId`, `creationDate`, `isScreenshot`,
`isScreenRecording`, `isVideo`, `isEdited`, and the asset's `pixelArea` (one area; which resolution floor
applies is decided from `isVideo`). Every fact SHALL be readable off the asset itself, with no resource
round-trip — a fact that lives on a *resource* cannot be one, because a consumer resolving only a count
would then have to choose between paying for it and admitting on doubt, and the same policy would yield
different sets at different consumers. The interpretation of raw PhotoKit
values (the `mediaSubtypes` bitmask, the `mediaType` integer) into those neutral facts SHALL live in the
iOS adapter (`iosMain`), where the PhotoKit bit constants belong and are pinned; `:domain` (`model/`) SHALL
NOT reference a PhotoKit bitmask. The selection rules (capability `photo-selection-policy`) SHALL read only
neutral `AssetFacts`, so the policy is platform-neutral and a second platform produces the same facts from
its own media model.

The interpretation SHALL be covered by iOS-target tests (`iosSimulatorArm64Test`); the policy logic SHALL
remain covered by `commonTest` over neutral facts (no hand-built bitmask in a policy test).

#### Scenario: model never sees a bitmask

- **WHEN** `:domain` source is inspected for PhotoKit media-subtype/media-type values
- **THEN** none appear — the mapping from raw values to `AssetFacts` lives only in the iOS adapter

### Requirement: A resource's content type is the resolved MIME
`Resource.contentType` SHALL carry the **resolved MIME** content type — the same value the platform
adapter resolves iOS-side via `UTType.preferredMIMEType` (falling back to
`application/octet-stream`). The platform's own type identifier SHALL NOT occupy that field, and
SHALL NOT be carried across the enumeration seam at all: the adapter resolves it and reports the
MIME.

This closes a silence rather than changing a rule. The existing seam requires the MIME to be
resolved iOS-side and carried as a raw fact, but names no field to hold it — so the resource was
built with the UTI in `contentType` and the MIME alongside it in `metadata`, and `contentType` is
what `edge-upload-provider` sends as the upload's `Content-Type` header. Naming the field's value is
what stops the two from diverging again.

The change is observable only in the stored object's `Content-Type`: the ledger row already prefers
the metadata MIME, the device manifest is built from ledger rows, and the import path branches on
the manifest's value — so every consumer inside the system already reads the MIME.

#### Scenario: A resource is built from a platform asset
- **WHEN** the enumeration seam maps a platform resource into a `Resource`
- **THEN** `contentType` holds the resolved MIME, and no platform type identifier crosses the seam
  in any field

#### Scenario: An upload request is built
- **WHEN** the upload provider reads `resource.contentType` for the `Content-Type` header
- **THEN** the stored object carries a valid MIME media type rather than a platform type identifier

#### Scenario: The platform cannot resolve a MIME
- **WHEN** the platform returns no preferred MIME for a resource
- **THEN** the adapter reports `application/octet-stream`, and admission is unaffected — the
  fallback is a content-type answer, never an exclusion

### Requirement: A deny-everything policy narrows the platform fetch to nothing

The platform read seam SHALL translate a policy that admits no asset into a native query that returns **no**
asset, rather than issuing an unnarrowed fetch and relying on the caller's in-memory admission to discard
every result.

This is a **liveness** requirement, matching the one that already forces the capture-date lower bound into
the query. The whole-library enumeration — a cold start with no discovery cursor — is the path that carries
a predicate, and it is the path where an unnarrowed fetch costs one synchronous platform round-trip per
asset. Without this translation a membership that contributes nothing would pay a full library walk on
every cold start to arrive at the empty set its own configuration already stated.

The translation SHALL be built from a comparison that is never satisfiable on a key the platform is known
to evaluate correctly. It SHALL NOT be built from any query form whose emptiness is an artefact of the
platform's query parser rather than its semantics: such a form returns nothing only for as long as the
platform continues to mis-evaluate it, and would begin returning the **entire library** if the platform ever
evaluated it correctly — the worst possible direction for a membership that shares nothing.

Correctness SHALL NOT depend on this translation. The caller's in-memory admission remains authoritative, so
an untranslated deny-everything rule costs a full walk and never a wrong admitted set — consistent with
every other narrowing being an optimization only.

The paths that carry **no** predicate — the incremental change-feed walk, which fetches by identifiers the
feed supplied, and the partial-grant selection observer, which holds an already-fetched result — are bounded
by construction and SHALL NOT require a separate short-circuit.

#### Scenario: A cold-start enumeration for a non-contributing membership returns nothing
- **WHEN** a whole-library enumeration is performed for a membership whose policy admits no asset
- **THEN** the native query returns no asset, so no per-asset platform round-trip is paid

#### Scenario: The translation does not rest on a parser artefact
- **WHEN** the deny-everything rule is translated into a native query
- **THEN** the query is an unsatisfiable comparison on a key the platform evaluates correctly, so a platform
  release that corrects an unrelated query-parser defect cannot turn it into a query matching every asset

#### Scenario: An untranslated deny-everything rule is slow, not wrong
- **WHEN** a platform cannot express the deny-everything rule in its native query
- **THEN** the enumeration returns assets and the caller's admission rejects all of them, so the admitted
  set is still empty

#### Scenario: The predicate-less paths need no short-circuit
- **WHEN** the incremental change-feed walk or the partial-grant selection observer supplies candidates for
  a membership whose policy admits no asset
- **THEN** the candidates are rejected by the caller's admission, and no additional gate is required,
  because both paths are already bounded to a change delta or a hand-picked selection

### Requirement: The count for a non-contributing membership costs no per-asset read

The own-device status total and the join-time shareable-count preview SHALL both report **zero** for a
membership whose policy admits no asset, and SHALL reach that answer without paying a per-asset platform
round-trip.

The requirement is stated as an outcome rather than as a mechanism, because either a caller-side
short-circuit or a native query that returns nothing satisfies it. What SHALL NOT satisfy it is walking the
library asset by asset to discover that none is admitted.

#### Scenario: The status total is zero without a per-asset walk
- **WHEN** the own-device status total is computed for a membership whose policy admits no asset
- **THEN** it reports zero, and no per-asset platform round-trip is paid

#### Scenario: The shareable-count preview is zero without a per-asset walk
- **WHEN** the join-time preview is computed with sharing off
- **THEN** it reports zero, and no per-asset platform round-trip is paid
