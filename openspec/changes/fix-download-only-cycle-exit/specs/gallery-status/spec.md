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
insufficient for the same reason: a `SelectionPolicy.None` membership uploads nothing, so any non-zero count
pegs the screen below 100% forever.

`SelectionPolicy` SHALL be a **required** parameter of the count, with no default and no "unscoped" value.
This is a privacy requirement, not an ergonomic one: no value and no absent-argument fallback may scope the
count to the whole library. A default is prohibited in both polarities — a permissive one admitting every
capture date spans the entire library from the beginning of time, and a fail-closed one (`None`) makes a
contributing member's screen read "In sync" over a count of nothing. A `SelectionPolicy.None` membership
counts `0` **without enumerating the library**, and a device with no membership has no scope to count at
all — the composition root simply does not refresh, and `N` remains at its seeded `0`.

The permissive polarity is now unrepresentable rather than merely prohibited: a contributing policy carries
its capture-date lower bound as a non-null field (capability `photo-selection-policy`), so there is no
`Admitting` value spanning every capture date for a default to select. The count SHALL obtain its bound by
exhausting the sealed policy — handling the non-contributing membership on its own branch and receiving a
non-null bound otherwise — rather than by testing an accessor that could report an absent floor for either
reason.

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

#### Scenario: A non-contributing membership counts zero without enumerating

- **WHEN** the count is refreshed for a `SelectionPolicy.None` membership whose library holds photos
- **THEN** `size.value` is `0` and no library enumeration is performed

#### Scenario: The non-contributing case is distinguished from the bound, not merged with it

- **WHEN** the count resolves the capture-date bound it will walk with
- **THEN** the non-contributing membership is recognised as its own case before any bound is read, so
  "contributes nothing" is never reached by way of an absent floor
