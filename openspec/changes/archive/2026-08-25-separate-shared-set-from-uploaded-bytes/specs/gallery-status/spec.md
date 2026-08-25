## MODIFIED Requirements

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

## ADDED Requirements

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
