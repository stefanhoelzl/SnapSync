# gallery status Specification

## Purpose

The live photo-library size seam: the count of photos currently in the device library, used by the
status projection as the sync total `N`. A platform-backed `StateFlow` (the `GalleryStatusSource`
seam) that `:domain:status` combines with the ledger and permission — so the total reflects photos
the instant they are added, before the background extension records anything. Lives in
`:domain:gallery`; PhotoKit-backed on iOS, a settable in-memory implementation on JVM. Authoritative
design: docs/design.md §2.4.
## Requirements
### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in a new `:domain:gallery` module (package
`app.snapsync.gallery`) whose `size` is a `StateFlow<Int>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived count (never a placeholder
or negative sentinel). The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

The count is the **whole library** count in this version (matching the extension's current,
unfiltered discovery). When discovery later filters by capture date and media type, the same predicate
SHALL drive this count so the two never diverge.

#### Scenario: Current size is available synchronously

- **WHEN** a consumer reads `size.value` immediately after obtaining a `GalleryStatusSource`
- **THEN** it receives a real non-negative `Int`, never a placeholder or default sentinel

#### Scenario: Empty library reports zero

- **WHEN** the photo library contains no photos
- **THEN** `size.value` is `0`

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
current library as a list of resources, each carrying `(filename, assetId, version)` — where
`filename` is the upload key (the reinstall-stable identity, `<assetId>-<kind>.<ext>`), `assetId`
groups a photo's resources, and `version` is the content-identity proof (the asset modification
timestamp). This is the **single shared derivation** of those fields: the iOS background-upload
producer's full-enumeration path SHALL delegate to it, so the same `(filename, version)` is computed
wherever enumeration happens (the join seed and the producer agree byte-for-byte). The iOS
implementation SHALL be PhotoKit-backed; `:domain:gallery` SHALL also provide a settable in-memory
implementation for the JVM harness and tests. The seam SHALL remain in `:domain:gallery` so its types
never reach `:domain:presentation`'s compile classpath (per "Module placement keeps the seam off
presentation").

#### Scenario: Enumeration yields per-resource identity and version
- **WHEN** a consumer enumerates a library with photos that each have one or more resources
- **THEN** it receives one entry per resource, each carrying that resource's `filename`, its photo's
  `assetId`, and the asset's `version`

#### Scenario: The producer and the seed derive identical keys and versions
- **WHEN** the upload producer enumerates a resource and the join seam enumerates the same resource
- **THEN** both yield the same `filename` and the same `version` (one shared derivation)

#### Scenario: Fake enumeration is settable
- **WHEN** a test sets the in-memory enumeration to a list of resources
- **THEN** enumerating returns exactly those resources, on JVM and on the iOS simulator

