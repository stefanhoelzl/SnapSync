## MODIFIED Requirements

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
