## MODIFIED Requirements

### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in a new `:domain:gallery` module (package
`app.snapsync.gallery`) whose `size` is a `StateFlow<Int>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived count (never a placeholder
or negative sentinel). The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

The count SHALL be **scoped by the membership's selection policy** (capability `photo-selection-policy`),
carried as a `Contribution`: its **participation direction**, its capture-date cutoff, **and** its origin
exclusions — the same policy, from the same value, that scopes the upload cycle's discovery, so the count and
the admitted set never diverge. There is no whole-library count.

Scoping by the cutoff alone is insufficient: an origin-excluded asset that counted toward `N` but was never
uploaded would peg completeness permanently below 100% and hold the joined screen at "pending" forever — the
same failure the cutoff scoping exists to prevent. Scoping by cutoff and origin alone is insufficient for the
same reason: a `Contribution.None` membership uploads nothing, so any non-zero count pegs the screen below
100% forever.

`Contribution` SHALL be a **required** parameter of the count, with no default and no "unscoped" value. A
membership with no contribution counts `0` **without enumerating the library**, and a device with no
membership has no scope to count at all — the composition root simply does not refresh, and `N` remains at
its seeded `0`.

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

- **WHEN** the count is refreshed for a `Contribution.None` membership whose library holds photos
- **THEN** `size.value` is `0` and no library enumeration is performed
