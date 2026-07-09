## MODIFIED Requirements

### Requirement: Per-device, per-membership capture-date cutoff

The system SHALL support a **capture-date cutoff** that scopes a device's participation in an event to
photos taken at or after a chosen instant. The cutoff SHALL be **per-device** and **per-membership**: it
is the joining device's own choice for its membership in a specific event, chosen at join time, and it
SHALL NOT be sent to the backend, SHALL NOT be inherited from the event, and SHALL NOT be imposed by the
event's host. In v1 the cutoff SHALL be **immutable after join** (set once at the confirm; changed only
by leaving and re-joining). The cutoff SHALL be carried on the per-event membership state (v1: the single
persisted `EventConfig`; the data model SHALL be shaped so a future set of memberships each carries its
own cutoff without relocating the field).

A cutoff SHALL be **required**: a membership without one is not a representable state. The persisted
membership's cutoff field SHALL be non-null, and every consumer of the cutoff SHALL receive a non-null
value. There SHALL be no scope in which a membership admits the whole library.

#### Scenario: The cutoff is a device-local choice, never sent to the backend
- **WHEN** a device joins an event with a chosen cutoff
- **THEN** the cutoff is persisted on that device's membership and no request carries it to the backend

#### Scenario: The cutoff is immutable after join in v1
- **WHEN** a device is already joined with a cutoff and re-provisions the same event
- **THEN** the cutoff is unchanged (no re-pick), consistent with the join being a no-op for the already-joined event

#### Scenario: A membership always carries a cutoff
- **WHEN** any joined membership is read, by the app process or the upload extension process
- **THEN** its cutoff is a non-null cutoff string, and no code path exists by which a joined membership
  admits assets of every capture date

### Requirement: Cutoff string format invariant

A cutoff SHALL be represented as an ISO-8601 UTC timestamp in the exact form
`yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), **second** precision, **no** timezone offset, **no** fractional
seconds — byte-identical in shape to the string a bare `NSISO8601DateFormatter()` produces for an
asset's `creationDate`. This invariant is mandatory for two reasons: the cutoff is compared against
`creationDate` **lexicographically** (`creationDate >= cutoff`), where a differing shape (an offset,
fractional seconds) would compare incorrectly; and the iOS walk parses the cutoff with a bare
`NSISO8601DateFormatter` (whose default `.withInternetDateTime` options reject a fractional second) to
bound its `PHFetchOptions` fetch, so an off-shape cutoff silently costs the bounded fetch.

Every cutoff SHALL be produced in this shape. In particular the default sourced from an event's fetched
`createdAt` SHALL be **normalized** into it, NOT used verbatim: the backend mints `createdAt` with
`new Date().toISOString()`, which always carries **milliseconds** (`2026-07-09T19:24:17.182Z`).
Normalization SHALL truncate toward the earlier instant (dropping the fraction), the inclusive direction,
so a photo taken within the cutoff's own second is admitted rather than lost. "Now" and manually-picked
local values SHALL likewise be converted into this shape.

The empty string SHALL NOT be used as a cutoff value or as a decode-time default for a cutoff. The
lexicographic compare is **asymmetric**: an undated *asset* (`creationDate == ""`) is excluded by any
non-empty cutoff, whereas an undated *cutoff* (`""`) admits every asset, because every string is `>= ""`.
An empty cutoff is therefore equivalent to whole-library scope while presenting as a present, non-null
value.

#### Scenario: A cutoff compares correctly against creationDate
- **WHEN** a cutoff `2026-07-06T14:32:11Z` is compared against an asset `creationDate`
- **THEN** the comparison is a plain lexicographic `>=` and yields the correct at-or-after result

#### Scenario: The event-createdAt default is normalized to second precision
- **WHEN** the default cutoff is taken from an event's fetched `createdAt` of `2026-07-09T19:24:17.182Z`
- **THEN** the persisted cutoff is `2026-07-09T19:24:17Z` — the fraction truncated (earlier, inclusive),
  so the lexicographic compare and the iOS fetch predicate both accept it

#### Scenario: A cutoff carrying fractional seconds still bounds the platform fetch
- **WHEN** a cutoff persisted by an earlier build carries the backend's raw milliseconds
- **THEN** the platform walk still parses it and bounds its fetch, rather than dropping the predicate and
  walking the whole library

#### Scenario: An undated asset is treated as before any cutoff
- **WHEN** an asset has no `creationDate` (the enumerator emits an empty string) and a cutoff is set
- **THEN** the lexicographic compare excludes it (an empty string is not `>=` a non-empty cutoff), so the undated asset is out of scope

#### Scenario: An empty cutoff is never a valid value
- **WHEN** a cutoff value is produced, persisted, or defaulted
- **THEN** it is never the empty string, because `creationDate >= ""` holds for every asset and would
  silently admit the whole library

### Requirement: The cutoff scopes the own-device status total

The own-device upload **total** `N` SHALL count only the device's own assets that are **in scope** — at
or after the cutoff — the same set the upload cycle admits (`N` is the count driving the joined screen's
sync health, capability `sync-status`). A pre-cutoff asset SHALL NOT count toward `N`, because it is never
uploaded; counting it would peg completeness permanently below 100% and hold the screen at "pending"
forever. Because a membership always carries a cutoff, the total is always a scoped count.

#### Scenario: A pre-cutoff asset does not inflate the total
- **WHEN** the library holds a pre-cutoff asset and an in-scope asset, and the in-scope asset is uploaded
- **THEN** the total counts only the in-scope asset, so the joined screen reaches "in sync" (not a
  perpetual "pending")

#### Scenario: The total is always scoped by a cutoff
- **WHEN** the own-device total is computed for a joined membership
- **THEN** it is computed against that membership's non-null cutoff, with no unscoped whole-library branch

### Requirement: Cutoff byte-upload filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource whose owning asset's
`creationDate` precedes the applicable cutoff, before the resource reaches the ledger/engine. The filter
SHALL be applied to **both** the full enumeration and the incremental change-token walk, and SHALL be
tier-agnostic (it governs the OS-driven PhotoKit extension tier and the app-driven `URLSession` tier
alike, since both funnel through the shared cycle). The applicable cutoff SHALL be expressed as the
**minimum** cutoff across the device's current memberships — so a photo is uploaded when it is in scope
for **at least one** joined event — which in v1 (single membership) reduces to that membership's single
cutoff. The applicable cutoff is always non-null. The engine and ledger SHALL remain date-blind; the
exclusion happens entirely in the cycle's resource selection.

The cutoff filter in the cycle's resource selection SHALL remain the **authoritative** exclusion. A
platform enumeration MAY additionally narrow its fetch by capture date as an optimization, but the
cycle's filter SHALL still run over whatever that fetch returns, so a platform fetch can never widen or
narrow the admitted set.

#### Scenario: Pre-cutoff resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the cutoff
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: The filter covers the incremental walk
- **WHEN** the incremental change-token walk surfaces a changed asset whose `creationDate` precedes the cutoff
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships
- **WHEN** the device has memberships with cutoffs `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's cutoff)

#### Scenario: A platform fetch narrowed by date does not change the admitted set
- **WHEN** the platform enumeration returns a superset of the in-scope assets (for example because its
  date predicate was deliberately widened)
- **THEN** the cycle's cutoff filter still excludes every pre-cutoff resource, so the admitted set is
  identical to that of an unnarrowed fetch

<!--
No `## REMOVED Requirements` block: nothing removed here is a whole requirement.

The two whole-library scenarios that encoded "absent cutoff ⇒ whole library" —
`A null cutoff is whole-library` (under *Per-device, per-membership capture-date cutoff*) and
`A whole-library scope counts everything` (under *The cutoff scopes the own-device status total*) —
are scenarios, and both are dropped by the MODIFIED requirement bodies above, which carry their full
updated content. The rationale and migration live in proposal.md and design.md.
-->

