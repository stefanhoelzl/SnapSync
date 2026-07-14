## ADDED Requirements

### Requirement: The event's start date is a floor on every membership's cutoff

An event SHALL carry a **start date** (`startsAt`, capability `event-creation`), set once by the host at
creation and immutable thereafter. It SHALL act as a **floor** on every membership's capture-date
cutoff: a membership's **effective cutoff** SHALL be `max(chosen, startsAt)`.

The floor SHALL be applied **at join time** — `JoinEvent` SHALL compute `max(chosen, startsAt)` and
persist **that** value as the membership's cutoff. Because `startsAt` is immutable, the clamped result is
stable for the life of the membership. The upload cycle SHALL therefore continue to filter on exactly
**one** cutoff, and `startsAt` SHALL NOT reach the upload path at all — no runtime gate, no second
filter, no new branch in the one code path where an error means uploading a member's whole library.

The clamp SHALL apply to **every** cutoff that enters a membership, with **no exemption** — the
interactive pick, the "Now" preset, the "Event start" preset, and the dev/test `minPhotoDate` override
carried on a decoded deeplink (capability `deeplink-config`) alike. The deeplink override is not exempt
precisely *because* it is the dangerous one: a `minPhotoDate` in the link payload is decoded from any
`snapsync://` URL, so an unclamped override would let a hostile QR carrying `autoJoin=true` and a
distant-past cutoff auto-confirm a join at near-whole-library scope **without a tap**. Under the clamp
that value is raised to the event's own start.

The floor can only ever **narrow** a membership's scope, never widen it. This is what makes a
host-supplied date safe: a host who sets a distant-past start lowers the *default* a joiner sees, but the
joiner still sees the resulting instant before committing and can choose a later cutoff; a host cannot
cause any photo taken before `startsAt` to be uploaded, and cannot raise a member's cutoff above the
member's own choice.

#### Scenario: The chosen cutoff is clamped up to the event start
- **WHEN** a member joins an event whose `startsAt` is `2026-07-14T18:00:00Z` and chooses a cutoff of
  `2026-07-14T12:00:00Z`
- **THEN** the persisted cutoff is `2026-07-14T18:00:00Z` — the floor — and the member's photos from
  before the event started are never uploaded

#### Scenario: A chosen cutoff above the floor is honored unchanged
- **WHEN** a member joins the same event and chooses a cutoff of `2026-07-14T21:00:00Z`
- **THEN** the persisted cutoff is `2026-07-14T21:00:00Z`, the member freely choosing above the floor

#### Scenario: The floor is applied once, at join, not in the upload cycle
- **WHEN** the upload cycle runs for a joined membership
- **THEN** it filters on the single persisted cutoff, and `startsAt` appears nowhere in the upload path

#### Scenario: A deeplink's dev/test cutoff override is clamped too
- **WHEN** a deeplink carrying `autoJoin = true` and an explicit `minPhotoDate` earlier than the event's
  `startsAt` is decoded
- **THEN** the auto-fired confirm persists `max(minPhotoDate, startsAt)` — the override cannot lower the
  membership below the event's start

#### Scenario: A membership's photos are never earlier than the event
- **WHEN** any joined membership's cutoff is read, by the app process or the upload extension process
- **THEN** it is at or after that event's `startsAt`, so no photo captured before the event started can
  be uploaded to it or listed in its manifest

### Requirement: Nothing syncs before the event starts

While an event's `startsAt` is in the future, **no photo SHALL be uploaded to it**. This SHALL hold
without any runtime gate, scheduler, or new filter, as a consequence of the floor: a photo's capture date
cannot lie in the future, so while the effective cutoff (`>= startsAt`) is itself in the future, no asset
satisfies `creationDate >= cutoff` and the upload cycle admits nothing.

Downloads SHALL NOT be gated. An unstarted event has no uploaded objects to pull, so gating them would
add a second enforcement site for no behavioral gain, and a member joining a **live** event still pulls
its history immediately.

When `startsAt` passes, the first upload SHALL ride the platform's **natural** triggers — a new photo, the
OS's own invocation cadence, or an app foreground. No scheduled wake-up SHALL be introduced: on the
iOS ≥ 26.1 PhotoKit tier the extension's `process()` is OS-scheduled and cannot be forced at all, so a
wake-up on the app-driven tier alone would make the two tiers diverge at the single most visible moment
of the feature.

#### Scenario: A future-start event uploads nothing
- **WHEN** a device is joined to an event whose `startsAt` is in the future, and the upload cycle runs
  over a library of photos
- **THEN** no resource is admitted, no upload job is created, and the ledger gains no entry — because
  every photo's `creationDate` precedes the effective cutoff

#### Scenario: Uploads begin on a natural trigger after the start passes
- **WHEN** the event's `startsAt` passes and a photo is subsequently captured (or the OS invokes the
  cycle, or the app is foregrounded)
- **THEN** the cycle runs and admits every in-scope photo, with no scheduled wake-up having been required

#### Scenario: Downloads are not gated by the start
- **WHEN** a device joins an event that has already started and foreign photos exist in the union
- **THEN** the download machinery runs immediately and imports them, the start date having gated nothing

## MODIFIED Requirements

### Requirement: Per-device, per-membership capture-date cutoff

The system SHALL support a **capture-date cutoff** that scopes a device's participation in an event to
photos taken at or after a chosen instant. The cutoff SHALL be **per-device** and **per-membership**: it
is the joining device's own choice for its membership in a specific event, chosen at join time, and it
SHALL NOT be sent to the backend. In v1 the cutoff SHALL be **immutable after join** (set once at the
confirm; changed only by leaving and re-joining). The cutoff SHALL be carried on the per-event membership
state (v1: the single persisted `EventConfig`; the data model SHALL be shaped so a future set of
memberships each carries its own cutoff without relocating the field).

The event SHALL supply the cutoff's **default** (its `startsAt`, capability `event-creation`) and its
**floor** (see *The event's start date is a floor on every membership's cutoff*). The surviving safety
invariant — the one this capability exists to protect — is directional:

- the event's start can only **narrow** a membership's scope, never widen it beyond the member's own
  choice: the member is always free to choose a **later** cutoff than the event's start, and the value
  the member is committing to SHALL be visible on the join surface **before** the confirm;
- a host SHALL NOT be able to cause any photo taken before `startsAt` to be uploaded;
- a host SHALL NOT be able to raise a member's cutoff above what that member chose.

This supersedes the prior absolute rule that the cutoff "SHALL NOT be inherited from the event and SHALL
NOT be imposed by the event's host". That rule was written when the event's only temporal fact was
`createdAt` — an implementation detail of when a JSON object was written — and a host-supplied date could
only have been a *widening* default with no compensating bound. A start date that is **both** the default
**and** the floor inverts that: the host bounds the event's contents from below, and the member chooses
freely above.

A cutoff SHALL be **required**: a membership without one is not a representable state. The persisted
membership's cutoff field SHALL be non-null, and every consumer of the cutoff SHALL receive a non-null
value. There SHALL be no scope in which a membership admits the whole library.

#### Scenario: The cutoff is a device-local choice, never sent to the backend
- **WHEN** a device joins an event with a chosen cutoff
- **THEN** the cutoff is persisted on that device's membership and no request carries it to the backend

#### Scenario: The member sees the value being committed
- **WHEN** the join surface offers the cutoff
- **THEN** the resulting instant is rendered on the surface before the confirm, so a host-supplied
  default is an informed one and never a hidden one

#### Scenario: The host cannot widen a member beyond the member's own choice
- **WHEN** an event's `startsAt` is far in the past and the member selects a later cutoff
- **THEN** the member's selection is persisted unchanged, and no photo before it is uploaded

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

Every cutoff SHALL be produced in this shape. The event's **`startsAt`** — the cutoff's default and floor
— SHALL be carried in this shape **on the wire**: the backend requires the canonical form on
`POST /events` and stores it verbatim (capability `event-creation`), so `startsAt` needs **no**
client-side normalization and is directly comparable. This is deliberately unlike `createdAt`, which the
backend mints with `new Date().toISOString()` and which therefore always carries **milliseconds**
(`2026-07-09T19:24:17.182Z`); a cutoff derived from a `createdAt` — including the `startsAt` a legacy
marker's read synthesizes from it — SHALL be **normalized** into the canonical shape, NOT used verbatim.
Normalization SHALL truncate toward the earlier instant (dropping the fraction), the inclusive direction,
so a photo taken within the cutoff's own second is admitted rather than lost. "Now" and manually-picked
local values SHALL likewise be converted into this shape.

The empty string SHALL NOT be used as a cutoff value or as a decode-time default for a cutoff. The
lexicographic compare is **asymmetric**: an undated *asset* (`creationDate == ""`) is excluded by any
non-empty cutoff, whereas an undated *cutoff* (`""`) admits every asset, because every string is `>= ""`.
An empty cutoff is therefore equivalent to whole-library scope while presenting as a present, non-null
value. For the same reason the empty string SHALL NOT be an accepted `startsAt` — a floor of `""` is no
floor at all.

#### Scenario: A cutoff compares correctly against creationDate
- **WHEN** a cutoff `2026-07-06T14:32:11Z` is compared against an asset `creationDate`
- **THEN** the comparison is a plain lexicographic `>=` and yields the correct at-or-after result

#### Scenario: startsAt needs no normalization
- **WHEN** an event's `startsAt` is read from `GET /events/:eventId`
- **THEN** it is already in the canonical `yyyy-MM-dd'T'HH:mm:ss'Z'` shape (the backend rejected any
  other) and is used as a cutoff default and floor without conversion

#### Scenario: A createdAt-derived startsAt is normalized to second precision
- **WHEN** a legacy marker carries no `startsAt` and the read synthesizes one from a `createdAt` of
  `2026-07-09T19:24:17.182Z`
- **THEN** the cutoff derived from it is `2026-07-09T19:24:17Z` — the fraction truncated (earlier,
  inclusive), so the lexicographic compare and the iOS fetch predicate both accept it

#### Scenario: A cutoff carrying fractional seconds still bounds the platform fetch
- **WHEN** a cutoff persisted by an earlier build carries the backend's raw milliseconds
- **THEN** the platform walk still parses it and bounds its fetch, rather than dropping the predicate and
  walking the whole library

#### Scenario: An undated asset is treated as before any cutoff
- **WHEN** an asset has no `creationDate` (the enumerator emits an empty string) and a cutoff is set
- **THEN** the lexicographic compare excludes it (an empty string is not `>=` a non-empty cutoff), so the undated asset is out of scope

#### Scenario: An empty cutoff is never a valid value
- **WHEN** a cutoff value is produced, persisted, or defaulted — including an event's `startsAt`
- **THEN** it is never the empty string, because `creationDate >= ""` holds for every asset and would
  silently admit the whole library

### Requirement: Injected time source for now and local conversion

The system SHALL obtain "now" and convert a manually-picked **local** date+time into the UTC `…Z`
cutoff string in `commonMain` via an **injected** time source (a `Clock`), never via `expect`/`actual`,
so the conversion and formatting are unit-testable on both the JVM and the iOS simulator. The injected
time source SHALL be the single origin of "now" for the cutoff and SHALL produce the exact UTC `…Z`
shape the format invariant requires.

The same injected time source SHALL be the origin of "now" for the event's **start date** — both the
create screen's default (see capability `event-creation-ui`) and the not-started comparison
(`startsAt > now`, see capability `sync-status-screen`). There SHALL be exactly one origin of "now" in
`commonMain`, so a test can move time on both the JVM and the iOS simulator.

#### Scenario: Now is obtained from the injected clock and formatted to the invariant
- **WHEN** the "now" cutoff is requested
- **THEN** the injected clock supplies the instant and it is formatted to the UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` shape

#### Scenario: A local pick converts to the UTC cutoff
- **WHEN** a user picks a local date and time
- **THEN** it is converted to the corresponding UTC instant and formatted to the `…Z` cutoff shape, comparable against `creationDate`

#### Scenario: The start date shares the one clock
- **WHEN** the create screen defaults a start date to "now", or the not-started state compares
  `startsAt` against "now"
- **THEN** both read the same injected clock, and a test can drive them by substituting it

#### Scenario: Conversion is testable without platform code
- **WHEN** the cutoff conversion/formatting is exercised in a test
- **THEN** it runs in `commonTest` against an injected clock on both JVM and the iOS simulator, with no `expect`/`actual`
