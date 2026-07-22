## MODIFIED Requirements

### Requirement: Per-device, per-membership capture-date cutoff

The system SHALL support a **capture-date range** that scopes a device's participation in an event to
photos taken within a chosen window `[from, until]`: a **lower bound** `from` (the member contributes only
photos taken at or after it) and an **inclusive upper bound** `until` (the member contributes only photos
taken at or before it). Both bounds SHALL be **per-device** and **per-membership**: they are the joining
device's own choice for its membership in a specific event, first set at join time, and they SHALL NOT be
sent to the backend. The range SHALL be **changeable in place after join** via `reconfigure-membership` —
never by re-scanning (re-provisioning an already-joined event remains a no-op) — and any changed bound
SHALL re-apply its clamp (`from` re-clamped to the `startsAt` **floor**, `until` re-clamped to the `endsAt`
**ceiling**). The range SHALL be carried on the per-event membership state (v1: the single persisted
`EventConfig`; the data model SHALL be shaped so a future set of memberships each carries its own range
without relocating the fields).

The event SHALL supply the lower bound's **default** (its `startsAt`, capability `event-creation`) and its
**floor** (see *The event's start date is a floor on every membership's cutoff*), and the upper bound's
**default** (its `endsAt`, capability `event-creation` / `event-limits`) and its **ceiling** (see *The
event's end date is a ceiling on every membership's upper bound*). The default range is therefore the full
event window `[startsAt, endsAt]` — narrowing on doubt, never widening. The surviving safety invariant —
the one this capability exists to protect — is directional:

- the event's start can only **narrow** a membership's scope, never widen it beyond the member's own
  choice: the member is always free to choose a **later** lower bound than the event's start, and the value
  the member is committing to SHALL be visible on the join surface **before** the confirm;
- the event's end can only **narrow** a membership's scope, never widen it beyond the member's own choice:
  the member is always free to choose an **earlier** upper bound than the event's end, and the value the
  member is committing to SHALL likewise be visible on the join surface **before** the confirm;
- a host SHALL NOT be able to cause any photo taken before `startsAt` to be uploaded;
- a host SHALL NOT be able to raise a member's lower bound above what that member chose, nor lower a
  member's upper bound below what that member chose.

The **upper bound is a reversal, on a changed premise, of the prior "lower bound only, none planned"
stance.** That stance assumed the event's `endsAt` was a coarse, server-fixed storage backstop
(`startsAt + 30d`), where a capture-date ceiling would silently drop genuine late event photos. The event's
`endsAt` is now **creator-chosen and precise** — the host's declared statement of when the event happened —
so a photo taken after it is a *non-event* photo, not a late event photo, and the excluded window is
**shown on the join surface before confirm**, so the exclusion is neither coarse nor silent. admit-on-doubt
is untouched for everything within the window: **every** photo whose capture date is `<= endsAt` is still
admitted (see *Origin exclusions admit on doubt*). Decision record:
`changes/add-event-date-range` (D1).

This supersedes the prior absolute rule that the cutoff "SHALL NOT be inherited from the event and SHALL
NOT be imposed by the event's host". That rule was written when the event's only temporal fact was
`createdAt` — an implementation detail of when a JSON object was written — and a host-supplied date could
only have been a *widening* default with no compensating bound. A start date that is **both** the default
**and** the floor, and an end date that is **both** the default **and** the ceiling, invert that: the host
bounds the event's contents from below and above, and the member chooses freely inside.

A lower bound `from` SHALL be **required**: a membership without one is not a representable state. The
persisted membership's lower-bound field SHALL be non-null, and every consumer SHALL receive a non-null
value. There SHALL be no scope in which a membership admits the whole library.

#### Scenario: The range is a device-local choice, never sent to the backend
- **WHEN** a device joins an event with a chosen range
- **THEN** both bounds are persisted on that device's membership and no request carries them to the backend

#### Scenario: The member sees the values being committed
- **WHEN** the join surface offers the range
- **THEN** both resulting instants are rendered on the surface before the confirm, so a host-supplied
  default (start and end) is an informed one and never a hidden one

#### Scenario: The host cannot widen a member beyond the member's own choice
- **WHEN** an event's `startsAt` is far in the past, its `endsAt` far in the future, and the member selects
  a later lower bound and an earlier upper bound
- **THEN** the member's selection is persisted unchanged, and no photo before the chosen lower bound or
  after the chosen upper bound is uploaded

#### Scenario: Re-provisioning an already-joined event leaves the range unchanged
- **WHEN** a device is already joined with a range and re-provisions (or re-scans) the same event
- **THEN** both bounds are unchanged (no re-pick), consistent with the join being a no-op for the already-joined event

#### Scenario: The range can be changed in place after join
- **WHEN** a joined member opens the reconfigure surface (capability `reconfigure-membership`) and confirms a different range
- **THEN** the persisted bounds are replaced with the new values, the lower bound clamped to the `startsAt`
  floor and the upper bound clamped to the `endsAt` ceiling, without leaving or re-enrolling, and the next
  upload cycle applies them

#### Scenario: A membership always carries a lower bound
- **WHEN** any joined membership is read, by the app process or the upload extension process
- **THEN** its lower bound is a non-null cutoff string, and no code path exists by which a joined membership
  admits assets of every capture date

### Requirement: Selection filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource that the selection policy does
not admit — whose owning asset's `creationDate` **precedes the applicable lower bound**, whose owning
asset's `creationDate` **exceeds the applicable upper bound**, **or** which any origin exclusion rejects —
**before the resource reaches the ledger/engine**. The capture-date test SHALL be the **inclusive range**
`from <= creationDate <= until`: the lower bound admits at or after `from`, and the upper bound admits at or
before `until` (inclusive). Both comparisons SHALL be plain **lexicographic** compares over the canonical
`yyyy-MM-dd'T'HH:mm:ss'Z'` second-precision shape (see *Cutoff string format invariant*), so a differing
shape on either bound compares incorrectly. The filter SHALL be applied to **both** the full enumeration and
the incremental change-token walk, and SHALL be **tier-agnostic** (it governs the OS-driven PhotoKit
extension tier and the app-driven `URLSession` tier alike, since both funnel through the shared cycle). The
applicable lower bound SHALL be expressed as the **minimum** lower bound across the device's current
memberships — so a photo is uploaded when it is in scope for **at least one** joined event — which in v1
(single membership) reduces to that membership's single lower bound. The applicable lower bound is always
non-null. The engine and ledger SHALL remain policy-blind; the exclusion happens entirely in the cycle's
resource selection.

The filter in the cycle's resource selection SHALL remain the **authoritative** exclusion, and SHALL live in
the **platform-free upload-cycle core**, not in untested platform wiring, so it is exercised in `commonTest`. A
platform enumeration MAY additionally narrow its fetch (by capture date — including an upper narrowing by
the upper bound — media subtype, or pixel dimensions) as an optimization, but the cycle's filter SHALL still
run over whatever that fetch returns, so **a platform fetch can never widen or narrow the admitted set**.

The origin exclusions SHALL be applied **before** the device-manifest hook, whereas the capture-date range
SHALL NOT be. The origin exclusions are **event-independent** (a screenshot is a screenshot in every event),
while the range is **per-membership** — so pre-filtering by origin costs the device-global accumulator no
per-event flexibility, while pre-filtering by date would (capability `device-manifest`).

#### Scenario: Pre-lower-bound resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the lower bound `from`
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: Post-upper-bound resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` exceeds the upper bound `until`
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: A resource captured exactly at the upper bound is admitted
- **WHEN** the cycle discovers a resource whose asset `creationDate` equals the upper bound `until` (and is
  at or after `from` and origin-admitted)
- **THEN** it is admitted, because the upper bound is inclusive (`creationDate <= until`)

#### Scenario: Origin-excluded resources never reach the engine
- **WHEN** the cycle discovers a resource whose owning asset an origin rule rejects
- **THEN** the resource is dropped before the engine and before `retainAssets`, so no upload job is created
  and the ledger gains no entry for it

#### Scenario: The filter covers the incremental walk
- **WHEN** the incremental change-token walk surfaces a changed asset the policy does not admit
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships
- **WHEN** the device has memberships with lower bounds `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's lower bound)

#### Scenario: A platform fetch narrowed by date or origin does not change the admitted set
- **WHEN** the platform enumeration returns a superset of the admitted assets (for example because its
  predicate was deliberately widened, or because it cannot express an exclusion the policy makes)
- **THEN** the cycle's filter still excludes every non-admitted resource, so the admitted set is identical to
  that of an unnarrowed fetch

#### Scenario: The manifest sees the origin-filtered set but not the date-filtered set
- **WHEN** a cycle discovers a screenshot and a pre-lower-bound camera photo
- **THEN** the device-manifest hook is fed neither the screenshot (origin-excluded before the hook) nor, in
  the event's manifest, the out-of-range photo (excluded by the per-event date projection) — while the
  out-of-range photo remains in the device-global accumulator and the screenshot does not

## ADDED Requirements

### Requirement: The event's end date is a ceiling on every membership's upper bound

An event SHALL carry an **end date** (`endsAt`, capability `event-creation` / `event-limits`),
creator-chosen at creation and immutable thereafter. It SHALL act as a **ceiling** on every membership's
capture-date upper bound: a membership's **effective upper bound** SHALL be `min(chosen, endsAt)`.

The ceiling SHALL be applied **at join time** — `JoinEvent` SHALL compute `min(chosen, endsAt)` (mirroring
the `max(chosen, startsAt)` floor) and persist **that** value as the membership's upper bound. Because
`endsAt` is immutable, the clamped result is stable for the life of the membership. The upper bound's
default SHALL be `endsAt` itself — the full event window — so a member who picks nothing contributes every
in-window photo.

The clamp SHALL apply to **every** upper bound that enters a membership, with **no exemption** — the
**Custom** interactive pick, the **Event end** option (capability `join-event`), and the dev/test
`maxPhotoDate` override carried on a decoded event link (capability `event-link`) alike. As with the floor
clamp, the persisted `min(chosen, endsAt)` clamp is the **authoritative** ceiling; a join surface's picker
MAY additionally enforce the ceiling at pick time (rendering post-ceiling days unselectable), but that UI
enforcement mirrors the clamp and does not replace it.

The ceiling can only ever **narrow** a membership's scope, never widen it beyond the member's own pick: the
member is always free to choose an **earlier** upper bound than the event's end, and the value being
committed SHALL be visible on the join surface **before** the confirm. A host cannot cause any photo taken
after `endsAt` to be uploaded, and cannot lower a member's upper bound below the member's own choice.

For memberships stored before this capability carried an upper bound, an absent upper bound SHALL be treated
as **unbounded** (no ceiling) until reconcile backfills `min(default, endsAt) = endsAt` (capability
`event-rejoin-reconciliation`), so nothing is silently dropped mid-upgrade.

#### Scenario: The chosen upper bound is clamped down to the event end
- **WHEN** a member joins an event whose `endsAt` is `2026-07-21T23:00:00Z` and chooses an upper bound of
  `2026-07-28T12:00:00Z`
- **THEN** the persisted upper bound is `2026-07-21T23:00:00Z` — the ceiling — and the member's photos from
  after the event ended are never uploaded

#### Scenario: A chosen upper bound below the ceiling is honored unchanged
- **WHEN** a member joins the same event and chooses an upper bound of `2026-07-20T21:00:00Z`
- **THEN** the persisted upper bound is `2026-07-20T21:00:00Z`, the member freely choosing below the ceiling

#### Scenario: The upper bound defaults to the full event window
- **WHEN** a member joins an event whose `endsAt` is `2026-07-21T23:00:00Z` and picks no explicit upper bound
- **THEN** the persisted upper bound is `2026-07-21T23:00:00Z` — the event's end — so every in-window photo is admitted

#### Scenario: An event link's dev/test upper override is clamped too
- **WHEN** an event link carrying `autoJoin = true` and an explicit `maxPhotoDate` later than the event's
  `endsAt` is decoded
- **THEN** the auto-fired confirm persists `min(maxPhotoDate, endsAt)` — the override cannot raise the
  membership's upper bound above the event's end

#### Scenario: A membership's photos are never later than the event
- **WHEN** any joined membership's persisted upper bound is read, by the app process or the upload extension process
- **THEN** it is at or before that event's `endsAt`, so no photo captured after the event ended can be
  uploaded to it or listed in its manifest

#### Scenario: An unbackfilled legacy membership is treated as unbounded above
- **WHEN** a membership stored before this capability is read and carries no upper bound, and reconcile has
  not yet backfilled it
- **THEN** the upper bound is treated as unbounded (no ceiling), so no in-scope photo is silently dropped
  before the backfill runs
