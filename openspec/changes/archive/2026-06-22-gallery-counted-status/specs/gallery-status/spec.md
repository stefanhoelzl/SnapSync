## ADDED Requirements

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

The iOS implementation SHALL back `size` with a PhotoKit count and live in `:app:ios` (wiring-only,
untested). The `:domain:gallery` module SHALL provide a test fake whose count is **settable**, so the
JVM desktop harness and integration tests can drive any total — including discovery-lag (`N` greater
than the ledger's completed count) and overshoot (`N` less than the ledger's completed count) — without
a device.

#### Scenario: Fake count is settable

- **WHEN** a test sets the fake gallery source's size to 47
- **THEN** `size.value` is `47` and a collector observes the new value

### Requirement: Module placement keeps the seam off presentation

`GalleryStatusSource` and its fake SHALL live in `:domain:gallery`. `:domain:status` SHALL depend on
`:domain:gallery` with **implementation** scope only, so gallery types never reach
`:domain:presentation`'s compile classpath.

#### Scenario: Presentation compiles without the gallery seam

- **WHEN** `:domain:presentation` is compiled
- **THEN** `:domain:gallery` is not on its compile classpath, and no gallery type is reachable from
  presentation code
