# photo-selection-policy Specification

## Purpose

**Which of my photos enter this event.** One policy, applied at one place, deciding what a member contributes.

It has two halves, and they answer different questions.

**When was it taken?** A per-device, per-membership **capture-date cutoff**, chosen at join: the member
contributes only photos taken from a moment they pick onward. Without it, joining an event shares a device's
entire photo library — and because every uploaded asset enters the event union, every other member downloads
it too. The joiner faced an all-or-nothing choice between sharing years of unrelated photos and not joining.
The cutoff makes contribution scopeable, and it defaults to the event's **start date** (`startsAt` — the
instant the host sets at creation, capability `event-creation`), which is almost always what the member means.

The event's start is also a **floor** beneath the member's choice: the persisted cutoff is
`max(chosen, startsAt)`, clamped once at join. So the host bounds the event's contents from below — no photo
taken before the event began can ever be uploaded to it — while the member remains free to choose any later
cutoff. The event can only ever **narrow** a membership's scope, never widen it beyond what the member picked,
and the value being committed is visible on the join surface before the confirm.

**What is it?** The cutoff bounds *when*, but says nothing about *what*. Inside the event window a camera roll
also accumulates screenshots, memes, and media received over messaging apps and the browser — none of which
anyone at the event took, and all of which would otherwise upload to the event and land on every other
member's phone. The **origin exclusions** subtract those.

The exclusions can only *subtract*, never *infer*: PhotoKit exposes no "this device's camera took this" flag
on any iOS through 26. So the policy excludes what is certainly not a capture and **admits on doubt** — a
stray uploaded meme is visible and harmless, while an event photo that silently fails to upload is a failure
of the product's core promise that the user cannot even notice, let alone fix.

**One policy gates both directions of the member's own contribution** — the byte upload and the manifest
listing — so a photo excluded from the upload cannot leak into the event through the listing. It also scopes
the own-device status total, so the screen counts what this device intends to share rather than everything it
owns.

Decision record: `changes/archive/2026-07-06-add-join-date-cutoff` (the cutoff);
`changes/archive/2026-07-14-add-event-start-date` (the event start as the cutoff's default **and** floor);
`changes/archive/…-add-photo-selection-policy` (the origin exclusions, and this capability's rename).
## Requirements
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
carried on a decoded event link (capability `event-link`) alike. The event-link override is not exempt
precisely *because* it is the dangerous one: a `minPhotoDate` in the link payload is decoded from any
event link, so an unclamped override would let a hostile QR carrying `autoJoin=true` and a
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

#### Scenario: An event link's dev/test cutoff override is clamped too
- **WHEN** an event link carrying `autoJoin = true` and an explicit `minPhotoDate` earlier than the event's
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

### Requirement: Participation direction is a selection input, carried as Contribution

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date cutoff and the origin exclusions. The policy answers *what does this member contribute?* — the
cutoff bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

The three inputs SHALL be carried to both policy consumers as a single value, `Contribution`, defined in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a —
visible to both policy consumers, `feature/upload` and `feature/status`):

- `Contribution.None` — the membership contributes nothing (`DownloadOnly`). It carries **no** cutoff,
  because a non-contributor has no cutoff to speak of.
- `Contribution.Since(cutoff)` — the membership contributes every admitted asset captured at or after
  `cutoff`.

`Contribution` SHALL be a **required** argument on both consumers, with **no default value**. A default is
prohibited in both polarities: a permissive default (`Since("")`) uploads the entire library from the
beginning of time, and a fail-closed default (`None`) makes a contributing member silently share nothing
while the screen reads "In sync" — the invisible failure this capability exists to prevent. The two states
being distinct constructors, rather than a cutoff plus a boolean, SHALL make "contributes nothing, and here
is the cutoff it is not using" unrepresentable.

The direction is a **per-membership** input, not a per-resource rule: it SHALL be applied **before** any
library walk, never as a per-asset filter within one. The walk costs one synchronous PhotoKit round-trip per
asset, so a non-contributor must never begin one to conclude it contributes nothing.

#### Scenario: A download-only membership contributes the empty set
- **WHEN** the membership's participation direction excludes upload
- **THEN** the selection policy admits no asset, regardless of any asset's capture date or origin

#### Scenario: The non-contributing case carries no cutoff
- **WHEN** a membership contributes nothing
- **THEN** it is expressed as `Contribution.None`, which carries no cutoff — the combination "contributes
  nothing, with a cutoff" cannot be constructed

#### Scenario: A non-contributor never walks the library
- **WHEN** the selection policy is applied for a `Contribution.None` membership
- **THEN** no library enumeration is performed — the empty result is reached before any per-asset walk begins

### Requirement: One policy gates both byte upload and manifest listing

A membership's **selection policy** SHALL gate **both** which of the device's photo bytes are uploaded
**and** which of its assets are listed in that event's device manifest — the policy being its participation
direction, its capture-date cutoff, and the origin exclusions, carried together as `Contribution`. The two
SHALL use the **same** admitted set, so the set uploaded equals the set listed. Because the event union exposes each
device's manifest-listed assets to other members, the policy thereby governs both this device's backup scope
and what other members can download from it. A photo excluded by the policy SHALL neither have its bytes
uploaded nor appear in the manifest (and therefore SHALL NOT enter the event union).

A photo excluded by the policy but listed in the manifest would be strictly worse than one merely uploaded:
it would enter the event union and every other member would attempt to download bytes that were never
uploaded.

A `Contribution.None` membership admits nothing, so it SHALL upload no bytes **and** write no manifest
listing any asset. Publishing a non-contributor's assets to the manifest would be the same failure in its
most complete form: the member's entire library would enter the event union, be offered to every other
member, and 404 for all of them — while the member was told they would share nothing.

#### Scenario: An in-scope photo is both uploaded and shared
- **WHEN** a photo's `creationDate` is at or after the membership's cutoff and no origin exclusion applies
- **THEN** its bytes are uploaded and it is listed in the device manifest (eligible for the event union)

#### Scenario: An out-of-scope photo is neither uploaded nor shared
- **WHEN** a photo's `creationDate` precedes the membership's cutoff
- **THEN** its bytes are not uploaded and it is not listed in the manifest, so no other member can download it

#### Scenario: An origin-excluded photo is neither uploaded nor shared
- **WHEN** a photo is at or after the cutoff but is excluded by an origin rule (for example a screenshot)
- **THEN** its bytes are not uploaded **and** it does not appear in the device manifest, so it never enters
  the event union and no other member downloads it

#### Scenario: A non-contributing membership uploads nothing and lists nothing
- **WHEN** the membership is `Contribution.None` and the library holds admitted-looking photos
- **THEN** no bytes are uploaded and no device manifest listing any asset is written, so nothing enters the
  event union

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count only the device's own assets that the **selection policy
admits** — a contributing membership, at or after the cutoff, **and** not origin-excluded — the same set the
upload cycle admits (`N` is the count driving the joined screen's sync health, capability `sync-status`). An
asset the policy excludes SHALL NOT count toward `N`, because it is never uploaded; counting it would peg
completeness permanently below 100% and hold the screen at "pending" forever.

A `Contribution.None` membership SHALL report `N = 0`, computed **without enumerating the library**. `N` is
otherwise a parallel computation that no upload gate feeds, so without this the total would report a library
the cycle will never upload, and the joined screen would show an upload arrow that never settles.

The status total and the upload cycle SHALL apply the **same** policy, from the **same** `Contribution`
value. They are computed by different components (`OwnDeviceGalleryStatusSource` in the app process,
`UploadCycle` in the upload path), so this identity is a requirement, not an implementation coincidence.

#### Scenario: A pre-cutoff asset does not inflate the total
- **WHEN** the library holds a pre-cutoff asset and an in-scope asset, and the in-scope asset is uploaded
- **THEN** the total counts only the in-scope asset, so the joined screen reaches "in sync" (not a
  perpetual "pending")

#### Scenario: An origin-excluded asset does not inflate the total
- **WHEN** the library holds a screenshot taken after the cutoff, and every admitted asset is uploaded
- **THEN** the screenshot does not count toward `N`, and the joined screen reaches "in sync" rather than
  holding permanently below 100%

#### Scenario: A non-contributing membership totals zero without a walk
- **WHEN** the own-device total is computed for a `Contribution.None` membership whose library holds photos
- **THEN** `N` is `0` and no library enumeration is performed

#### Scenario: There is no unscoped whole-library total
- **WHEN** the own-device total is computed for any joined membership
- **THEN** it derives from that membership's `Contribution` — a contributing one counted against its
  non-null cutoff, a non-contributing one reported as `0` — and there is no unscoped whole-library branch

#### Scenario: The total and the cycle admit the same set
- **WHEN** the own-device total and the upload cycle are computed for the same membership
- **THEN** both are derived from the same `Contribution` value, so the counted set equals the admitted set

### Requirement: Selection filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource that the selection policy does
not admit — whose owning asset's `creationDate` precedes the applicable cutoff, **or** which any origin
exclusion rejects — **before the resource reaches the ledger/engine**. The filter SHALL be applied to **both**
the full enumeration and the incremental change-token walk, and SHALL be **tier-agnostic** (it governs the
OS-driven PhotoKit extension tier and the app-driven `URLSession` tier alike, since both funnel through the
shared cycle). The applicable cutoff SHALL be expressed as the **minimum** cutoff across the device's current
memberships — so a photo is uploaded when it is in scope for **at least one** joined event — which in v1
(single membership) reduces to that membership's single cutoff. The applicable cutoff is always non-null. The
engine and ledger SHALL remain policy-blind; the exclusion happens entirely in the cycle's resource selection.

The filter in the cycle's resource selection SHALL remain the **authoritative** exclusion, and SHALL live in
the **platform-free upload-cycle core**, not in untested platform wiring, so it is exercised in `commonTest`. A
platform enumeration MAY additionally narrow its fetch (by capture date, media subtype, or pixel dimensions)
as an optimization, but the cycle's filter SHALL still run over whatever that fetch returns, so **a platform
fetch can never widen or narrow the admitted set**.

The origin exclusions SHALL be applied **before** the device-manifest hook, whereas the capture-date cutoff
SHALL NOT be. The origin exclusions are **event-independent** (a screenshot is a screenshot in every event),
while the cutoff is **per-membership** — so pre-filtering by origin costs the device-global accumulator no
per-event flexibility, while pre-filtering by date would (capability `device-manifest`).

#### Scenario: Pre-cutoff resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the cutoff
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: Origin-excluded resources never reach the engine
- **WHEN** the cycle discovers a resource whose owning asset an origin rule rejects
- **THEN** the resource is dropped before the engine and before `retainAssets`, so no upload job is created
  and the ledger gains no entry for it

#### Scenario: The filter covers the incremental walk
- **WHEN** the incremental change-token walk surfaces a changed asset the policy does not admit
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships
- **WHEN** the device has memberships with cutoffs `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's cutoff)

#### Scenario: A platform fetch narrowed by date or origin does not change the admitted set
- **WHEN** the platform enumeration returns a superset of the admitted assets (for example because its
  predicate was deliberately widened, or because it cannot express an exclusion the policy makes)
- **THEN** the cycle's filter still excludes every non-admitted resource, so the admitted set is identical to
  that of an unnarrowed fetch

#### Scenario: The manifest sees the origin-filtered set but not the cutoff-filtered set
- **WHEN** a cycle discovers a screenshot and a pre-cutoff camera photo
- **THEN** the device-manifest hook is fed neither the screenshot (origin-excluded before the hook) nor, in
  the event's manifest, the pre-cutoff photo (excluded by the per-event date projection) — while the
  pre-cutoff photo remains in the device-global accumulator and the screenshot does not

### Requirement: Origin exclusions admit on doubt

The selection policy's origin rules SHALL exclude an asset only on a **certain** signal, and SHALL admit it
otherwise. Where a rule cannot distinguish a received or generated asset from a captured one, the asset SHALL
be **admitted**.

This posture is asymmetric on purpose. An event photo that fails to upload is a silent failure of the
product's core promise, with no surface on which the user could even notice it, let alone correct it; a stray
uploaded meme is visible, harmless, and recoverable. The policy therefore SHALL NOT adopt any rule whose
failure mode is dropping a genuine capture — in particular, it SHALL NOT infer capture-origin from an
allowlist of camera pixel dimensions (which excludes every cropped photo and every panorama) nor from an
allowlist of `originalFilename` shapes (which excludes third-party camera apps).

#### Scenario: An unrecognized asset is admitted
- **WHEN** an asset matches no origin exclusion rule, and the policy cannot establish that it was received or
  generated rather than captured
- **THEN** the asset is admitted for upload

#### Scenario: A full-resolution received photo is admitted
- **WHEN** a full-resolution photo is received via AirDrop or saved from Messages, carrying no album
  membership and no excluded media subtype
- **THEN** the asset is admitted — the policy makes no attempt to exclude it, and this is a known, accepted gap

### Requirement: Screenshots, screen recordings and animated images are excluded

The policy SHALL exclude every asset whose `mediaSubtypes` carries the **screenshot** bit (`1 << 2`) or the
**screen-recording** bit (`1 << 19`), and every asset whose **primary** resource MIME content type is
`image/gif`.

None of these three is a camera capture under any reading. Screenshots are the highest-frequency non-captured
asset in a typical library, and both bits are exact — this is the one rule with perfect recall.

#### Scenario: A screenshot is excluded
- **WHEN** a discovered asset's `mediaSubtypes` has the screenshot bit set
- **THEN** it is excluded from upload and from the manifest, whatever its capture date

#### Scenario: A screen recording is excluded
- **WHEN** a discovered asset's `mediaSubtypes` has the screen-recording bit set
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A GIF is excluded
- **WHEN** a discovered asset's primary resource MIME content type is `image/gif`
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo carrying other subtypes is admitted
- **WHEN** a discovered asset carries only non-excluded subtype bits (for example panorama, HDR, live photo,
  or depth effect)
- **THEN** it is admitted — those subtypes are all camera captures

### Requirement: Resolution floors exclude compressed received media

The policy SHALL exclude an **image** whose pixel area is below **3 megapixels**, and a **video** whose pixel
area is below **1280 × 720**, **unless** the asset reports adjustments (`hasAdjustments`), in which case the
floor SHALL NOT be applied to it.

The floors SHALL be fixed `commonMain` constants and SHALL NOT be derived from the device's actual camera
dimensions at runtime. A device-derived floor is *tighter* on a better camera, and tighter means more false
drops — the opposite of the admit-on-doubt posture. The chosen floor sits more than 2× below the weakest
camera on the oldest supported device, while still sitting above the output of every common messaging app.

The **separate video floor is load-bearing, not a refinement**: 1080p video is 1920 × 1080 = 2.07 MP, which is
*below* the 3 MP image floor. A single shared floor would silently drop every 1080p recording — and 1080p is
the iOS capture default.

The **`hasAdjustments` guard is likewise load-bearing**: a photo cropped in Photos renders at its cropped size
and may fall under the floor despite being a genuine capture.

#### Scenario: A compressed received image is excluded
- **WHEN** a discovered image asset is 1600 × 1200 (1.9 MP) and reports no adjustments
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo is admitted
- **WHEN** a discovered image asset is 4032 × 3024 (12.2 MP)
- **THEN** it is admitted

#### Scenario: A cropped camera photo below the floor is admitted
- **WHEN** a discovered image asset is below 3 MP **and** reports `hasAdjustments`
- **THEN** it is admitted — the floor is not applied to an edited asset

#### Scenario: A 1080p recording is admitted
- **WHEN** a discovered video asset is 1920 × 1080 (2.07 MP, below the image floor)
- **THEN** it is admitted — the video floor is 1280 × 720, not the image floor

#### Scenario: A compressed received video is excluded
- **WHEN** a discovered video asset is 848 × 480 and reports no adjustments
- **THEN** it is excluded from upload and from the manifest

### Requirement: Denylisted album membership excludes an asset

The policy SHALL exclude every asset that is a member of an album whose title matches, **case-insensitively
and exactly**, an entry in a denylist of messaging- and social-application album titles. The denylist SHALL be
a `commonMain` constant.

The denylist SHALL match **user albums by title only**. Smart albums SHALL be matched by **subtype**, never by
title, because a smart album's title is system-localized.

Recall here is **known to be poor and is accepted**: on current iOS most messaging applications save directly
to the camera roll and create no album at all, and only WhatsApp is confirmed to create one (and only when its
"Save to Camera Roll" setting is enabled). The rule is retained because it is cheap — its cost is proportional
to the number of albums, not the number of assets — and strictly additive. It SHALL NOT be relied upon as the
primary mechanism for excluding received media; the resolution floors are.

**Under a limited grant the rule is inert.** A partial (`.limited`) grant exposes assets, not the album
structure: the user-album walk returns no albums even when a selected asset is a member of one (measured
on device with a real WhatsApp-album membership — the album was not surfaced and the lookup returned the
empty set, without error). The album seam's decision-free contract already makes an empty album walk an
empty exclusion set, so no code branches on permission — but the consequence SHALL be understood and not
overclaimed: a hand-picked photo in a denylisted album **will upload** under `LIMITED`. This is accepted
for the same reason the poor recall above is: the resolution floors — which read dimensions off the asset
itself and work under any grant — remain the primary received-media exclusion, and a deliberately selected
photo is the strongest admit signal the policy ever sees.

#### Scenario: An asset in a denylisted album is excluded
- **WHEN** a discovered asset is a member of an album titled `WhatsApp`
- **THEN** it is excluded from upload and from the manifest

#### Scenario: Title matching is case-insensitive and exact
- **WHEN** an album is titled `whatsapp`
- **THEN** it matches the denylist entry `WhatsApp`; an album titled `WhatsApp Backup` does not match

#### Scenario: An asset in a non-denylisted album is admitted
- **WHEN** a discovered asset is a member of a user album titled `Holiday 2026`
- **THEN** it is admitted — album membership excludes only against the denylist

#### Scenario: Under a limited grant the denylist excludes nothing
- **WHEN** a member with a `LIMITED` grant selects a photo that is a member of a denylisted album
- **THEN** the album lookup returns no membership (the album structure is not readable), the photo is
  admitted by this rule, and only the capture-date cutoff and the intrinsic origin rules (subtypes,
  resolution floors) can still exclude it

### Requirement: Album membership is read through a decision-free platform seam

The album-membership lookup SHALL be exposed as a **decision-free** platform verb: it takes a set of album
titles and returns the member asset identifiers, bounded by the capture-date cutoff. The platform SHALL supply
**facts only**; the **policy** — which titles are denied — SHALL live in tested `commonMain`, per the rule
(capability `event-album`) that no album decision may live in the untestable app or extension shell.

The lookup's cost SHALL be proportional to the number of denylisted albums, not to the number of assets in the
library: it SHALL NOT be implemented as a per-asset membership test.

#### Scenario: The platform seam makes no policy decision
- **WHEN** the album-membership seam is invoked
- **THEN** it receives the titles to look up as a parameter and returns the matching member asset identifiers,
  applying no denylist of its own

#### Scenario: The lookup is bounded by the cutoff
- **WHEN** the album-membership seam is invoked for a membership whose cutoff is `C`
- **THEN** it returns only member assets whose capture date is at or after `C` — the seam never enumerates the
  whole album

