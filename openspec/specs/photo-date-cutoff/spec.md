# photo-date-cutoff Specification

## Purpose

A per-device, per-membership **capture-date cutoff**, chosen at join: the member contributes only photos taken
from a moment they pick onward.

Without it, joining an event shares a device's entire photo library — and because every uploaded asset enters
the event union, every other member downloads it too. The joiner faced an all-or-nothing choice between
sharing years of unrelated photos and not joining. The cutoff makes contribution scopeable, and it defaults to
the event's creation time, which is almost always what the user means.

**One cutoff gates both directions of the member's own contribution** — the byte upload and the manifest
listing — so a photo excluded from the upload cannot leak into the event through the listing. It also scopes
the own-device status total, so the screen counts what this device intends to share rather than everything it
owns.

Decision record: `changes/archive/2026-07-06-add-join-date-cutoff`.

## Requirements
### Requirement: Per-device, per-membership capture-date cutoff

The system SHALL support a **capture-date cutoff** that scopes a device's participation in an event to
photos taken at or after a chosen instant. The cutoff SHALL be **per-device** and **per-membership**: it
is the joining device's own choice for its membership in a specific event, chosen at join time, and it
SHALL NOT be sent to the backend, SHALL NOT be inherited from the event, and SHALL NOT be imposed by the
event's host. In v1 the cutoff SHALL be **immutable after join** (set once at the confirm; changed only
by leaving and re-joining). The cutoff SHALL be carried on the per-event membership state (v1: the single
persisted `EventConfig`; the data model SHALL be shaped so a future set of memberships each carries its
own cutoff without relocating the field). An absent cutoff (`null`) SHALL mean **whole-library** scope
(no exclusion), preserving today's behavior for any membership without one.

#### Scenario: The cutoff is a device-local choice, never sent to the backend
- **WHEN** a device joins an event with a chosen cutoff
- **THEN** the cutoff is persisted on that device's membership and no request carries it to the backend

#### Scenario: The cutoff is immutable after join in v1
- **WHEN** a device is already joined with a cutoff and re-provisions the same event
- **THEN** the cutoff is unchanged (no re-pick), consistent with the join being a no-op for the already-joined event

#### Scenario: A null cutoff is whole-library
- **WHEN** a membership carries no cutoff
- **THEN** every not-deleted asset is in scope, exactly as before this change

### Requirement: Cutoff string format invariant

A cutoff SHALL be represented as an ISO-8601 UTC timestamp in the exact form
`yyyy-MM-dd'T'HH:mm:ss'Z'` — UTC (`Z`), **second** precision, **no** timezone offset, **no** fractional
seconds — byte-identical in shape to the string a bare `NSISO8601DateFormatter()` produces for an
asset's `creationDate`. This invariant is mandatory because the cutoff is compared against
`creationDate` **lexicographically** (`creationDate >= cutoff`); a differing shape (an offset, fractional
seconds) would compare incorrectly. The default cutoff sourced from an event's fetched `createdAt` SHALL
be used **verbatim** (it is already this shape); only "now" and manually-picked local values SHALL be
converted into this shape.

#### Scenario: A cutoff compares correctly against creationDate
- **WHEN** a cutoff `2026-07-06T14:32:11Z` is compared against an asset `creationDate`
- **THEN** the comparison is a plain lexicographic `>=` and yields the correct at-or-after result

#### Scenario: The event-createdAt default is reused verbatim
- **WHEN** the default cutoff is taken from an event's fetched `createdAt`
- **THEN** it is used as-is without reformatting, since it already carries the required UTC `…Z` shape

#### Scenario: An undated asset is treated as before any cutoff
- **WHEN** an asset has no `creationDate` (the enumerator emits an empty string) and a cutoff is set
- **THEN** the lexicographic compare excludes it (an empty string is not `>=` a non-empty cutoff), so the undated asset is out of scope

### Requirement: One cutoff gates both byte upload and manifest listing

A membership's cutoff SHALL gate **both** which of the device's photo bytes are uploaded **and** which of
its assets are listed in that event's device manifest — the two SHALL use the **same** cutoff value, so
the set uploaded equals the set listed. Because the event union exposes each device's manifest-listed
assets to other members, the cutoff thereby governs both this device's backup scope and what other
members can download from it. A photo excluded by the cutoff SHALL neither have its bytes uploaded nor
appear in the manifest (and therefore SHALL NOT enter the event union).

#### Scenario: An in-scope photo is both uploaded and shared
- **WHEN** a photo's `creationDate` is at or after the membership's cutoff
- **THEN** its bytes are uploaded and it is listed in the device manifest (eligible for the event union)

#### Scenario: An out-of-scope photo is neither uploaded nor shared
- **WHEN** a photo's `creationDate` precedes the membership's cutoff
- **THEN** its bytes are not uploaded and it is not listed in the manifest, so no other member can download it

### Requirement: The cutoff scopes the own-device status total

The own-device upload **total** `N` SHALL count only the device's own assets that are **in scope** — at
or after the cutoff — the same set the upload cycle admits (`N` is the count driving the joined screen's
sync health, capability `sync-status`). A pre-cutoff asset SHALL NOT count toward `N`, because it is never
uploaded; counting it would peg completeness permanently below 100% and hold the screen at "pending"
forever. With a `null` cutoff the total SHALL be the whole-library count (today's behavior).

#### Scenario: A pre-cutoff asset does not inflate the total
- **WHEN** the library holds a pre-cutoff asset and an in-scope asset, and the in-scope asset is uploaded
- **THEN** the total counts only the in-scope asset, so the joined screen reaches "in sync" (not a
  perpetual "pending")

#### Scenario: A whole-library scope counts everything
- **WHEN** the membership has no cutoff
- **THEN** the total is the whole own-device library count, unchanged from before this capability

### Requirement: Cutoff byte-upload filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource whose owning asset's
`creationDate` precedes the applicable cutoff, before the resource reaches the ledger/engine. The filter
SHALL be applied to **both** the full enumeration and the incremental change-token walk, and SHALL be
tier-agnostic (it governs the OS-driven PhotoKit extension tier and the app-driven `URLSession` tier
alike, since both funnel through the shared cycle). The applicable cutoff SHALL be expressed as the
**minimum** cutoff across the device's current memberships — so a photo is uploaded when it is in scope
for **at least one** joined event — which in v1 (single membership) reduces to that membership's single
cutoff, and under a `null` cutoff admits the whole library. The engine and ledger SHALL remain date-blind;
the exclusion happens entirely in the cycle's resource selection.

#### Scenario: Pre-cutoff resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the cutoff
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: The filter covers the incremental walk
- **WHEN** the incremental change-token walk surfaces a changed asset whose `creationDate` precedes the cutoff
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships
- **WHEN** the device has memberships with cutoffs `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's cutoff)

### Requirement: Injected time source for now and local conversion

The system SHALL obtain "now" and convert a manually-picked **local** date+time into the UTC `…Z`
cutoff string in `commonMain` via an **injected** time source (a `Clock`), never via `expect`/`actual`,
so the conversion and formatting are unit-testable on both the JVM and the iOS simulator. The injected
time source SHALL be the single origin of "now" for the cutoff and SHALL produce the exact UTC `…Z`
shape the format invariant requires.

#### Scenario: Now is obtained from the injected clock and formatted to the invariant
- **WHEN** the "now" cutoff is requested
- **THEN** the injected clock supplies the instant and it is formatted to the UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` shape

#### Scenario: A local pick converts to the UTC cutoff
- **WHEN** a user picks a local date and time
- **THEN** it is converted to the corresponding UTC instant and formatted to the `…Z` cutoff shape, comparable against `creationDate`

#### Scenario: Conversion is testable without platform code
- **WHEN** the cutoff conversion/formatting is exercised in a test
- **THEN** it runs in `commonTest` against an injected clock on both JVM and the iOS simulator, with no `expect`/`actual`

