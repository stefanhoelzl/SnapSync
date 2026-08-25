## MODIFIED Requirements

### Requirement: GalleryStatusSource seam

The gallery domain SHALL define `GalleryStatusSource` in `:domain`'s `ports/` zone (seated by
migration step 3a; born in the since-deleted `:domain:gallery` module) whose `size` is a `StateFlow<Int?>` — the count of photos currently in the
device photo library, used by the status projection as the sync total `N`. The current value SHALL
always be available synchronously and SHALL always be a real, source-derived value: either a **counted**
`Int`, or **`null`** meaning **the count has not been taken**. There SHALL be no placeholder count and
no negative sentinel. The seam exposes the count only; it does not expose individual assets, identity,
or per-asset state.

**`null` and `0` are different answers and SHALL NOT be conflated.** `0` asserts that this membership
contributes nothing; `null` asserts nothing at all. The distinction is load-bearing rather than
decorative: the status projection settles to "In sync" when the synced count reaches the total, so a
placeholder `0` standing in for an unread count reads as **"everything shared"** on a device that has
shared nothing and has not looked. A source that has never been refreshed SHALL report `null`, and
SHALL NOT report `0`.

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
counts `0` **without paying a per-asset read**; that `0` is a **counted** value and settles the screen
exactly as any other count does. A device with no membership has no scope to count at
all — the composition root simply does not refresh, and `N` remains **`null`**, *not counted*.

The permissive polarity is closed at the **one derivation** that turns a membership into a policy
(capability `photo-selection-policy`): it always emits the capture-date lower-bound rule, because the
persisted lower bound is non-null. The count SHALL NOT read a bound off the policy at all — it asks
`admits`, and takes any bound it needs for a platform query from the membership it already holds. There is
therefore no accessor that could report an absent floor for either reason.

An enumeration that **fails** SHALL leave the previous value in place — `null` if the count has never
been taken — and SHALL NOT publish a count it did not compute. The failure SHALL be reported at `Error`
severity (capability `diagnostic-logging`) and SHALL NOT propagate to the caller's siblings, because a
count that cannot be taken and a count of zero have different consequences and only the log can say
which occurred.

#### Scenario: Current size is available synchronously

- **WHEN** a consumer reads `size.value` immediately after obtaining a `GalleryStatusSource`
- **THEN** it receives either a real non-negative `Int` or `null`, never a placeholder count

#### Scenario: An un-refreshed source reports not-counted, not zero

- **WHEN** a `GalleryStatusSource` has never had its count refreshed
- **THEN** `size.value` is `null`, and a consumer can distinguish it from a membership that counted `0`

#### Scenario: Empty library reports a counted zero

- **WHEN** the count is refreshed for a contributing membership and the photo library contains no
  admitted photos
- **THEN** `size.value` is `0` — a counted zero, distinct from `null`

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
- **THEN** `size.value` is `0` — a counted zero — and the platform query is narrowed to match no asset,
  so no per-asset round-trip is paid to reach it

#### Scenario: The count never reads a floor off the policy

- **WHEN** the count needs a capture-date bound to scope a platform query
- **THEN** it takes that bound from the membership, and the policy offers no accessor whose absent value
  could mean either "contributes nothing" or "has no floor"

#### Scenario: A failed enumeration does not become a count

- **WHEN** an enumeration throws while the count has never successfully been taken
- **THEN** `size.value` remains `null`, the failure is logged at `Error` severity, and no count is
  published

### Requirement: Platform backing and a settable fake

The iOS implementation SHALL back `size` with a PhotoKit count. `:adapter:generic:fake` SHALL provide the
honest in-memory implementation (`InMemoryGalleryStatusSource`, re-homed from the deleted
`:domain:gallery` at migration step 10), whose count is a **constructor-injected state cell** of the
port's own nullable type — whoever owns the cell (a test, a `:test:world` wrapper) drives any total,
including **not-yet-counted** (`null`), discovery-lag (`N` greater than the ledger's completed count),
overshoot (`N` less than the ledger's completed count) and counted-empty (`0`), without a device; the
fake itself exposes only the port (the fake-honesty gate, `architecture-guards`).

The fake SHALL default its cell to `null`, so a test that does not state a count reproduces the
device's cold-launch state rather than a counted zero. A fake seeded with a count it was never given
is what made the un-counted state unreachable in tests.

#### Scenario: Fake count is driven through the owned cell

- **WHEN** a test constructs the in-memory gallery source over its own cell and writes 47 to it
- **THEN** `size.value` is `47` and a collector observes the new value

#### Scenario: Fake defaults to not-counted

- **WHEN** a test constructs the in-memory gallery source without supplying a count
- **THEN** `size.value` is `null`, and the consumer under test sees the un-counted state a cold launch
  produces
