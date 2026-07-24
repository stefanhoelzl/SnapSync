# photo-selection-policy Specification

## Purpose

**Which of my photos enter this event.** One policy, applied at one place, deciding what a member contributes.

It has two halves, and they answer different questions.

**When was it taken?** A per-device, per-membership **capture-date range** `[from, until]`, chosen at join:
the member contributes only photos taken within it. Without a lower bound, joining an event shares a device's
entire photo library — and because every uploaded asset enters the event union, every other member downloads
it too. The joiner faced an all-or-nothing choice between sharing years of unrelated photos and not joining.
The range makes contribution scopeable, and it defaults to the **whole event window** `[startsAt, endsAt]`
(the instants the host sets at creation, capability `event-creation`), which is almost always what the member
means.

The event's window is also a **floor and a ceiling** beneath the member's choice: the persisted range is
`[max(chosenFrom, startsAt), min(chosenUntil, endsAt)]`, clamped once at join. So the host bounds the event's
contents from both ends — no photo taken before the event began or after it ended can be uploaded to it —
while the member remains free to narrow either end. The event can only ever **narrow** a membership's scope,
never widen it beyond what the member picked, and the range being committed is visible on the join surface
before the confirm.

The upper bound is a **reversal** of an earlier decision (`2026-07-21-align-specs-with-mission`) that the
cutoff be *lower-bound-only*, and it rests on a changed premise rather than a changed principle. That decision
assumed `endsAt` was a coarse, server-fixed `startsAt + 30d` storage backstop: a capture ceiling there would
have silently dropped real event photos, so it was rightly refused. This capability's sibling `event-limits`
now makes `endsAt` **creator-chosen and precise** — the host's declared statement of when the event happened.
Under that premise a photo taken *after* the host's window is not a late event photo but a **non-event** photo,
and the window (both ends) is shown on the join surface before the member confirms — so the exclusion is
neither coarse nor silent. **admit-on-doubt is untouched for every photo `≤ endsAt`**: everything inside the
window is still admitted. The server-owned **lifetime** (capability `event-limits`) still governs *when the
event is over* — grace keeps in-window late uploads (capture `≤ endsAt`, transmitted late) landing past
`endsAt`, then expiry deletes the event; the capture ceiling and the lifetime coincide on one host-chosen
value but answer different questions (which photos vs. how long the event lives).

**What is it?** The range bounds *when*, but says nothing about *what*. Inside the event window a camera roll
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
`changes/archive/…-add-photo-selection-policy` (the origin exclusions, and this capability's rename);
`changes/archive/2026-07-21-align-specs-with-mission` (the lower-bound-only decision — later reversed);
`changes/archive/…-add-event-date-range` (the capture-date **range**: the upper bound, on a creator-chosen
precise `endsAt`, reversing lower-bound-only).
## Requirements
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
`changes/archive/2026-07-22-add-event-date-range` (D1).

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
**Custom** interactive pick, the **Now** option, the **Event start** option (capability `join-event`),
and the dev/test `minPhotoDate` override carried on a decoded event link (capability `event-link`) alike.
The event-link override is not exempt precisely *because* it is the dangerous one: a `minPhotoDate` in
the link payload is decoded from any event link, so an unclamped override would let a hostile QR carrying
`autoJoin=true` and a distant-past cutoff auto-confirm a join at near-whole-library scope **without a
tap**. Under the clamp that value is raised to the event's own start.

The persisted `max(chosen, startsAt)` clamp is the **authoritative** floor. The join surface's **Custom**
picker SHALL additionally enforce the floor **at pick time** — rendering pre-floor days unselectable and
coercing a confirmed value up to the floor (a day-grain calendar cannot forbid an earlier *hour* on the
floor's own day) — so the surface never displays or sends a cutoff the persisted clamp would overrule.
This UI enforcement mirrors the clamp; it does not replace it (the clamp still runs for every entry path,
including the ones that never touch the picker).

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

#### Scenario: A Custom pick below the floor is coerced up in the UI and clamped at join
- **WHEN** a member selects **Custom** and the picker would otherwise allow an hour earlier than the
  event's `startsAt` on the floor's own day
- **THEN** the surface coerces the displayed and sent value up to `startsAt`, and `JoinEvent` clamps it to
  `max(chosen, startsAt)` regardless — so no pre-floor cutoff is ever persisted

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

### Requirement: Participation direction is a selection input on the policy

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date range and the origin exclusions. The policy answers *what does this member contribute?* — the
range bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

All three SHALL be carried to every policy consumer as one already-decided value, `SelectionPolicy`, defined
in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a — the only
zone visible to every consumer, `feature/upload` and `feature/status` being mutually blind). It is the
rules, not the inputs from which rules could be derived: a consumer receives the decision, never the
material to re-decide (see *The admitted set is a single derivation every consumer receives*).

- `SelectionPolicy.None` — the membership contributes nothing (`DownloadOnly`). It carries **no** rules and
  therefore **no** bounds, because a non-contributor has none to speak of.
- `SelectionPolicy.Admitting(rules)` — the membership contributes every asset **all** of its `rules` admit.
  A contributing membership's rules always carry the capture-date lower bound (`CaptureAfter`).

`SelectionPolicy` SHALL be a **required** argument on every consumer, with **no default value**. This is a
privacy requirement, not an ergonomic one: there SHALL be no value, and no absent-argument fallback, under
which a membership admits the whole library. A default is prohibited in both polarities: a permissive
default (an `Admitting` policy carrying no `CaptureAfter` rule) uploads the entire library from the
beginning of time, and a fail-closed default (`None`) makes a contributing member silently share nothing
while the screen reads "In sync" — the invisible failure this capability exists to prevent. The two states
being distinct variants, rather than a rule list plus a boolean, SHALL make "contributes nothing, and here
are the rules it is not using" unrepresentable.

The direction is a **per-membership** input, not a per-asset rule: it SHALL be applied **before** any
library walk, never as a rule evaluated within one. The walk costs one synchronous PhotoKit round-trip per
asset, so a non-contributor must never begin one to conclude it contributes nothing.

#### Scenario: A download-only membership contributes the empty set
- **WHEN** the membership's participation direction excludes upload
- **THEN** the selection policy admits no asset, regardless of any asset's capture date or origin

#### Scenario: The non-contributing case carries no bounds
- **WHEN** a membership contributes nothing
- **THEN** it is expressed as `SelectionPolicy.None`, which carries no rules and no capture-date bounds —
  the combination "contributes nothing, and here is the cutoff it is not using" cannot be constructed

#### Scenario: A non-contributor never walks the library
- **WHEN** the selection policy is applied for a `SelectionPolicy.None` membership
- **THEN** no library enumeration is performed — the empty result is reached before any per-asset walk begins

### Requirement: The admitted set is a single derivation every consumer receives

There SHALL be exactly **one** derivation of a membership's **admitted set** — the assets the selection
policy admits — and every consumer SHALL obtain its answer from that one set rather than re-applying the
policy's rules itself. The policy SHALL be expressed as a value (`SelectionPolicy`) carrying its rules,
with a single `admits(facts)` decision; the admitted set is `candidates.filter { policy.admits }`. The
byte upload, the device manifest, the own-device status total `N`, and the join-time shareable-count
preview SHALL each derive from this set — upload uploads the admitted assets' resources, the manifest
lists them, `N` counts them, the preview counts them.

No consumer SHALL restate any of the policy's rules — not the capture-date range, the origin exclusions,
the echo suppression, nor the album denylist. A consumer that re-enumerates a rule is how the set drifts:
`add-event-date-range` added the capture-date **ceiling** to the byte filter and the preview but not the
manifest projection or `N`, so those two silently admitted post-ceiling photos into the manifest and the
status total while their bytes never uploaded — pegging the screen below 100% and offering foreign members
a resource that 404s. Making the admitted set one derivation makes that class of drift unrepresentable:
adding or changing a rule is one edit, and every consumer follows by construction.

Admission SHALL be applied at the point of **query** — when a consumer asks for the set or the count — and
no consumer SHALL treat an upstream-filtered structure as the admitted set. An upstream stage MAY exclude
assets earlier (the cycle drops origin-excluded resources before they reach the ledger, so they never reach
any reader downstream of it), but such a pre-filter enforces only a subset of the rules, so the consumer
still asks the policy. The single authoritative in-memory admission SHALL remain authoritative over every optimization
(platform fetch narrowing included, capability's *Selection filter* requirement) — a narrowing MAY reduce
what a walk returns but SHALL NOT change the admitted set.

#### Scenario: Every consumer admits the same set

- **WHEN** a membership's byte upload, device manifest, status total `N`, and join preview each resolve
  their assets for the same capture-date range
- **THEN** all four resolve the identical admitted set — no consumer includes or excludes an asset the
  others do not

#### Scenario: The ceiling reaches every consumer

- **WHEN** a membership has a capture-date ceiling and the device holds a photo captured after it
- **THEN** that photo is admitted by **no** consumer — it is neither uploaded, nor listed in the device
  manifest, nor counted in `N`, nor counted in the preview

#### Scenario: Adding a rule is one edit

- **WHEN** a new selection rule is introduced
- **THEN** it is defined once on `SelectionPolicy`, and every consumer's admitted set reflects it without
  any per-consumer change — no consumer carries a copy of the rule set

#### Scenario: A consumer cannot re-enumerate the policy

- **WHEN** the codebase is inspected for the capture-date comparison (`creationDate` against a bound)
- **THEN** it appears only inside the single `SelectionPolicy` admission, not at any consumer

### Requirement: Admission is decidable on asset facts alone

Every selection rule SHALL be decidable from an asset's **neutral facts** — properties readable without a
per-asset platform resource round-trip — or from an identifier set supplied to the policy. No rule SHALL
require reading an asset's resources to decide whether it is admitted.

This is what makes the admitted set **one** set rather than a family of approximations. A rule needing a
resource read forces every consumer to choose between paying for it (expensive, and pointless for a count)
and admitting on doubt (cheap, and a different answer) — so the same policy yields different sets at
different consumers, which is the drift class the *single derivation* requirement exists to close.

It also inverts the cost of the walk: because admission is settled before any resource is read, resources
SHALL be read only for assets the policy has **already admitted**. The previous ordering read every
in-scope asset's resources and then discarded the excluded ones, paying the round-trip for exactly the
assets it was about to throw away.

#### Scenario: A count reads no resources

- **WHEN** any consumer resolves only the size of the admitted set
- **THEN** it issues no per-asset resource read, because no rule needs one

#### Scenario: The preview and the status total agree exactly

- **WHEN** the join preview and the own-device status total are computed over the same library and the same
  membership bounds
- **THEN** they report the same count — neither admits an asset the other excludes

#### Scenario: An excluded asset costs no resource read

- **WHEN** the walk encounters an asset the policy excludes
- **THEN** the asset's resources are never fetched

### Requirement: One policy gates both byte upload and manifest listing

A membership's **selection policy** SHALL gate **both** which of the device's photo bytes are uploaded
**and** which of its assets are listed in that event's device manifest. Both SHALL derive from the **one
admitted set** (see *The admitted set is a single derivation every consumer receives*): the set uploaded
equals the set listed, because both read the same admitted set rather than each re-applying the policy.
Because the event union exposes each device's manifest-listed assets to other members, the policy thereby
governs both this device's backup scope and what other members can download from it. A photo excluded by
the policy — by capture-date range (**both** the lower cutoff and the upper ceiling), origin, echo, or
album — SHALL neither have its bytes uploaded nor appear in the manifest (and therefore SHALL NOT enter
the event union).

#### Scenario: Upload and manifest admit the identical set

- **WHEN** a device backs up for an event
- **THEN** the assets whose bytes it uploads and the assets its device manifest lists are the same set,
  each derived from the one admitted set

#### Scenario: A post-ceiling photo is in neither

- **WHEN** the device holds a photo captured after the membership's ceiling
- **THEN** its bytes are not uploaded and it is not listed in the manifest

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count exactly the assets in the membership's **admitted set** —
the same set the upload cycle admits — and SHALL NOT re-apply the policy's rules independently. `N` SHALL
respect the capture-date **range** (both bounds), the origin exclusions, the echo suppression, and the
album denylist by deriving from that set, so an asset the policy excludes never counts toward `N`.
Counting an excluded asset would peg completeness permanently below 100% and hold the screen at "pending"
forever — which is the concrete failure a floor-only `N` produced.

A `SelectionPolicy.None` (non-contributing) membership SHALL report `N = 0` without enumerating the library.

#### Scenario: N counts the admitted set, ceiling included

- **WHEN** the device holds photos both within and after the membership's capture-date range
- **THEN** `N` counts only those within the full range `[cutoff, until]` — a post-ceiling photo is not
  counted, so completeness can reach 100%

#### Scenario: N derives from the same set as upload

- **WHEN** the upload cycle admits a set and `N` is computed
- **THEN** `N` equals the size of that admitted own-device set, not a separately-filtered count

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
the **platform-free upload-cycle core**, not in untested platform wiring, so it is exercised in `commonTest`.

A platform enumeration MAY additionally narrow its fetch as an optimization. That narrowing SHALL be derived
by **translating the policy's own rules** — the platform receives the policy and pattern-matches the rules it
can express into its native query — rather than by re-stating a bound the caller flattened for it. Rules the
platform cannot express SHALL simply not be translated; an untranslated rule costs performance only, never
correctness, because the authoritative filter runs over whatever the fetch returns. **A platform fetch can
never widen or narrow the admitted set.** Because the rule set is a sealed type, adding a rule SHALL force
each platform translator to state explicitly whether it can express it.

Exactly one narrowing is **required** rather than advisory: the capture-date **lower bound** SHALL be pushed
into the platform query. That is a **liveness** property of the walk, not a correctness property of
admission — every rule is equally load-bearing for what is admitted, but an unbounded walk is watchdog-killed
before the authoritative filter ever runs.

The origin exclusions SHALL be applied before a resource reaches the ledger, so an origin-excluded asset
never gains a ledger row and therefore cannot appear in any device manifest — the manifest being a
projection of the ledger's `COMPLETED` rows (capability `device-manifest`). The capture-date bounds SHALL be
applied at **projection** time, against the membership's own policy, exactly as every other consumer applies
it. The projection SHALL receive the **policy**, not the inputs from which one could be derived, and SHALL
NOT take the ledger's contents for the admitted set.

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

#### Scenario: A new rule forces a translation decision

- **WHEN** a new selection rule is added to the sealed rule set
- **THEN** each platform translator fails to compile until it states explicitly whether it can express that
  rule, so a rule can never be silently left out of the narrowing

#### Scenario: The manifest lists only the admitted set

- **WHEN** a cycle discovers a screenshot, a pre-lower-bound camera photo, and an in-range camera photo
- **THEN** neither the screenshot nor the pre-lower-bound photo gains a ledger row, and the manifest
  projected from the ledger's `COMPLETED` rows — admitted by the same membership policy — lists only the
  in-range camera photo, so no consumer downstream can re-derive a different set

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

### Requirement: Screenshots and screen recordings are excluded

The policy SHALL exclude every asset whose `mediaSubtypes` carries the **screenshot** bit (`1 << 2`) or the
**screen-recording** bit (`1 << 19`).

Neither is a camera capture under any reading. Screenshots are the highest-frequency non-captured asset in a
typical library, and both bits are exact — this is the rule with perfect recall. Both are plain properties of
the asset, so the rule is decidable without reading any resource.

#### Scenario: A screenshot is excluded

- **WHEN** a discovered asset's `mediaSubtypes` has the screenshot bit set
- **THEN** it is excluded from upload and from the manifest, whatever its capture date

#### Scenario: A screen recording is excluded

- **WHEN** a discovered asset's `mediaSubtypes` has the screen-recording bit set
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo carrying other subtypes is admitted

- **WHEN** a discovered asset carries only non-excluded subtype bits (for example panorama, HDR, live photo,
  or depth effect)
- **THEN** it is admitted — those subtypes are all camera captures

#### Scenario: An ordinary animated image is excluded by the resolution floor

- **WHEN** a messenger or Giphy GIF (well below 3 megapixels, unedited) is discovered
- **THEN** it is excluded by the image resolution floor, without any rule reading its resources

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

### Requirement: The policy scopes the join-time shareable-count preview

The join surface's shareable-count preview SHALL count exactly the assets the candidate membership's
**admitted set** contains for the candidate capture-date range — the same admission every other consumer
applies, computed purely locally with no backend call. It SHALL respect **both** capture-date bounds, the
origin exclusions, the echo suppression, and the album denylist by deriving from that set. Because the
preview evaluates a **candidate** range (the one the member is choosing, before commit), it constructs the
policy over the candidate bounds; it SHALL NOT re-implement the rules — only supply the candidate bounds to
the one admission.

#### Scenario: The preview and the committed membership admit consistently

- **WHEN** a member picks a capture-date range on the join surface and then confirms it unchanged
- **THEN** the count the preview showed equals the size of the admitted own-device set the committed
  membership produces (echo/album state permitting), because both apply the one admission

### Requirement: The event's end date is a ceiling on every membership's upper bound

An event SHALL carry an **end date** (`endsAt`), creator-chosen at creation and immutable. It SHALL act as
a **ceiling** on every membership's capture-date upper bound: a membership's effective upper bound SHALL be
`min(chosen, endsAt)`, computed and persisted at join. The upper bound's default SHALL be `endsAt` (the
full event window).

The ceiling SHALL be a **required** value on a persisted membership — there is no unbounded ceiling. A
membership always carries a concrete upper bound (persisted from `min(chosen, endsAt)` at join), and every
consumer applies it via the admitted set. (The prior "absent upper bound treated as unbounded" allowance
for pre-ceiling configs is removed; see `join-event` and `event-rejoin-reconciliation` — a pre-ceiling
config is reconciled by `decouple-event-window-from-lifetime` before this change's strict decode.)

The ceiling SHALL apply to **every** admitting consumer with **no exemption**, because they all derive from
the one admitted set — the byte upload, the device manifest, the status total `N`, and the preview alike.
A host cannot cause any photo captured after `endsAt` to be uploaded, listed, counted, or previewed.

#### Scenario: The chosen upper bound is clamped to the event end

- **WHEN** a member joins an event and chooses an upper bound later than `endsAt`
- **THEN** the persisted upper bound is `endsAt`, and photos captured after it are excluded from every
  consumer

#### Scenario: The ceiling is required, not unbounded

- **WHEN** a membership is persisted
- **THEN** it carries a concrete capture-date upper bound; no membership is unbounded above

