## MODIFIED Requirements

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
