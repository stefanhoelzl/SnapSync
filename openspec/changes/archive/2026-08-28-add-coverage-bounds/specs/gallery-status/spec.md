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

That containment SHALL live in the source itself, not at a call site. It is an invariant about this
source's own state, and leaving its enforcement to callers makes a rule the source owns depend on every
caller remembering to protect it.

**Cancellation is not such a failure.** A cancelled enumeration SHALL propagate, and SHALL NOT be
reported at `Error` severity. Swallowing it would break structured concurrency, and the `Error` line
would reach the crash reporter (capability `crash-reporting`) for an ordinary teardown — an event that
says nothing about whether a count could be taken. The distinction matters because the containment above
is expressed with a construct that catches cancellation like anything else, so it has to be excluded
deliberately rather than by omission.

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

#### Scenario: A cancelled enumeration is not reported as a failed count

- **WHEN** the scope an enumeration runs in is cancelled while the walk is in flight
- **THEN** the cancellation propagates, no `Error`-severity line is logged, and no crash-report event
  is raised — because the count was not attempted and failed, it was abandoned

#### Scenario: A caller wraps the enumeration to protect its siblings

- **WHEN** a caller adds its own containment around the enumeration so a failure cannot cancel work
  beside it
- **THEN** that containment covers only what the caller itself does; the enumeration's own failure is
  already contained by the source, and duplicating it there is not what keeps the invariant true
