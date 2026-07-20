## MODIFIED Requirements

### Requirement: The join surface is a distinct, extensible UiState family

The capability SHALL own a `JoiningEvent` `UiState` family (carrying the `eventId` and a details phase
of loading / loaded-with-name / not-found / failed) and the full-screen "Join event" screen that
renders it, built from `App*` components (no Material 3 in any `App*` signature). In its **loaded** phase
the surface SHALL present the event name as a hero and the two participation decisions as plain on/off
**switches**, from which the membership's **direction** is **derived** — never chosen by name. There is
**no** direction selector, and the labels `Both` / `Upload only` / `Download only` SHALL NOT appear on
the surface.

The loaded phase SHALL present, in addition to the event name and the confirm (Join) / Cancel actions:

- a **Share switch** ("Share my photos"), **defaulting to on**. When on, the section SHALL state the
  origin exclusions (what the selection policy subtracts, capability `photo-selection-policy`), state the
  resulting cutoff instant **once, in the surface's heaviest type**, and present the capture-date cutoff
  choices (below). When **off**, the section SHALL state that nothing of the member's leaves the phone
  and SHALL **hide the cutoff entirely** (a member sharing nothing has no cutoff to pick).
- a **capture-date cutoff choice** within the Share section, a **three-option selector** — **Now** /
  **Event start** / **Custom** — **defaulting to Event start** (capability `photo-selection-policy`). The
  resulting instant is stated by the Share section's bold value line, so the choice rows SHALL NOT repeat
  it. Selecting **Custom** SHALL open the date+time picker directly; only the picker's confirm commits
  the choice, and cancelling SHALL leave the previous choice and its instant unchanged. The picker SHALL
  enforce the event's start as a **floor** (see capability `photo-selection-policy`). When the event's
  `startsAt` is **in the future**, the **Now** option SHALL be rendered **disabled** (it would clamp to
  the same instant as **Event start**).
- a **Receive switch** ("Receive everyone's photos" — titled to name the **source**, not "save to your
  library", which reads as backing up the member's own photos), **defaulting to on**. When on, the
  section SHALL state that others' photos arrive in the member's library automatically; when off, that
  the member will not receive the event's photos.
- a **standalone album opt-in** ("Create an album"), **defaulting to off** (opt-in), presented as a
  minor section nested under **neither** switch (capability `event-album`). When checked, the event's
  synced photos are gathered into a PhotoKit album titled after the event.

The membership's **direction** SHALL be **derived** from the two switches on confirm — share+receive →
`Both`, share only → `UploadOnly`, receive only → `DownloadOnly` — and crossed to `JoinEvent` together
with the chosen cutoff and the album opt-in (`saveToAlbum`). A switch SHALL NEVER auto-flip the other.
When **both switches are off**, the membership does nothing: the surface SHALL keep that state
representable, **disable Join**, and state the reason directly above the action, rather than silently
enabling either switch. The chosen cutoff SHALL cross to `JoinEvent` only when Share is on; the album
opt-in crosses in every case.

Cancel SHALL discard the pending join and return to the base screen (the create layer when no event is
configured).

#### Scenario: The join screen renders switches, the 3-option cutoff, and the standalone album section
- **WHEN** the `JoiningEvent` state is in its loaded phase for an event that has already started
- **THEN** the surface shows the event name hero, a **Share my photos** switch (on) with the resulting
  cutoff instant in bold and the Now / Event start / Custom choices (default Event start), a **Receive
  everyone's photos** switch (on), a standalone **Create an album** opt-in (off), and Join / Cancel — and
  no `Both` / `Upload only` / `Download only` label appears anywhere

#### Scenario: Direction is derived from the switches on confirm
- **WHEN** the user leaves both switches on and taps Join
- **THEN** the confirm crosses direction `Both`; **WHEN** only Share is on, it crosses `UploadOnly`;
  **WHEN** only Receive is on, it crosses `DownloadOnly`

#### Scenario: Both switches off is representable and blocks Join with a stated reason
- **WHEN** the user turns both the Share and the Receive switch off
- **THEN** the state is representable, Join is **disabled**, and a reason is shown above the action, and
  neither switch is auto-flipped to re-enable Join

#### Scenario: Share off hides the cutoff
- **WHEN** the user turns the Share switch off
- **THEN** the cutoff choices and the resulting-instant line are hidden, and the section states that
  nothing of the member's leaves the phone

#### Scenario: Custom opens the picker directly and only OK commits
- **WHEN** the user selects the **Custom** cutoff option
- **THEN** the date+time picker opens immediately; confirming it commits the picked (floored) instant as
  the cutoff, and cancelling it restores the previously-selected option and instant

#### Scenario: Now is disabled before the event starts
- **WHEN** the loaded event's `startsAt` is in the future
- **THEN** the **Now** option is rendered disabled, **Event start** remains selected, and the bold value
  shows the event's start instant

#### Scenario: The chosen cutoff, derived direction, and album choice cross on confirm
- **WHEN** the user adjusts the switches and cutoff, opts into the album, and taps Join
- **THEN** the derived direction, the chosen cutoff, and `saveToAlbum = true` are passed through the
  confirm intent into `JoinEvent`

#### Scenario: Cancel discards the pending join
- **WHEN** the user cancels on the join surface with no event configured
- **THEN** the pending join is discarded and the UI returns to the create layer

### Requirement: The join gate explains photo access before the first system dialog

The join gate SHALL show a full-screen **photo-access explainer** before the photo-library permission
dialog is ever raised, and the system dialog SHALL be reachable from that surface **only** via its
confirm action.

The explainer SHALL be a phase of the `JoiningEvent` family (not a dialog, not a separate `UiState`),
entered from the details fetch: when the fetch resolves to a loaded event, the gate SHALL enter the
**explain-access** phase instead of the loaded/confirm phase exactly when **both** hold:

- **no event is currently configured** (a *first* join — a switch, which is confirmed over the joined
  layer, SHALL never explain), and
- photo permission is `NOT_DETERMINED`.

Permission SHALL be read as a **snapshot at the moment the phase is chosen**, not observed — the phase
advances only by user action, so a permission change while the explainer is on screen SHALL NOT move it.

`GRANTED` and `DENIED` SHALL both go straight to the loaded/confirm phase. `DENIED` is excluded because
iOS raises the photo dialog at most once: from `DENIED` a request is a silent no-op, so an explainer
promising a dialog that cannot appear would be false. A `DENIED` joiner instead meets the joined layer's
existing `NeedsAccess` Settings affordance (capability `sync-status-screen`).

The explainer SHALL **name the event** it invites the user to, showing the same event hero rendered
across the Loading → explain-access → loaded/confirm → committing phases, so the event's identity never
jumps between phases. (This reverses the earlier decision to keep the explainer deliberately anonymous:
naming the event is what makes the surface continuous with the confirm phase it precedes.) The three
consent facts SHALL be presented as card rows beneath the hero, share-first: that photos the user takes
are shared automatically, that full library access is genuinely needed for **both** halves (sharing and
receiving), and that only photos after the capture-date the user chooses next are shared.

The explainer's **confirm** ("I understand") SHALL call `PhotoAccessRequester.request()` and advance to
the loaded/confirm phase in the same action. Because `request()` returns nothing and cannot suspend
(capability `permission-gate`), the phase SHALL advance immediately rather than await an outcome; the
system dialog lands over the confirm surface. The explainer's **cancel** SHALL discard the pending join
exactly as cancel does in every other phase — no enrollment, no config, no producer.

The explainer SHALL NOT auto-request: the system dialog fires only from the confirm action (CTA-only
priming). No other join-gate phase SHALL raise it.

The copy MUST NOT describe the app's function as "backing up" the user's library (consistent with
`event-creation-ui`), and MUST NOT claim a scope wider than the membership can produce.

Because a created event routes into the **same** gate (`onEventCreated` → the pending-join state), the
explainer SHALL cover a first-time creator and a first-time scanner identically, with no create-path
surface of its own.

#### Scenario: A first join with permission never asked explains before the dialog, naming the event
- **WHEN** the details fetch resolves to a loaded event, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the gate enters the explain-access phase showing the **named** event hero and the three consent
  facts, no permission request has been made, and the loaded/confirm surface is not yet shown

#### Scenario: The confirm requests permission and advances to the confirm surface
- **WHEN** the user takes the explainer's confirm action
- **THEN** `PhotoAccessRequester.request()` is called exactly once and the gate advances to the loaded/confirm phase carrying the same event name and default cutoff

#### Scenario: Cancelling the explainer abandons the join
- **WHEN** the user cancels on the explain-access phase
- **THEN** the pending join is discarded, no device is enrolled, no config is saved, no upload producer is enabled, and the UI returns to the create layer

#### Scenario: Already-granted access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `GRANTED`
- **THEN** the gate enters the loaded/confirm phase directly and no explainer is shown

#### Scenario: Previously-denied access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `DENIED`
- **THEN** the gate enters the loaded/confirm phase directly, no explainer is shown, and no permission request is made

#### Scenario: A switch never explains
- **WHEN** a deeplink for a different event is decoded while an event is already configured, and photo permission is `NOT_DETERMINED`
- **THEN** the switch confirmation is presented in its existing form and the explain-access phase is never entered

#### Scenario: A created event's first join explains too
- **WHEN** a freshly created event is routed into the pending-join gate, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the same explain-access phase is entered, naming the just-created event, with no create-specific surface

#### Scenario: The explainer never auto-requests
- **WHEN** the explain-access phase is rendered
- **THEN** no permission request is made until the user takes the confirm action

### Requirement: The cutoff row derives from the phase; it is never seeded at mount

The join surface's capture-date choice SHALL show a value derived from the **event's `startsAt`**, read
from whichever phase currently carries it — **not** from a value captured when the surface first rendered.

The surface is mounted at the **loading** phase (the pending join is created before the details fetch is
issued), so no start date exists at first render; the explain-access phase, when shown, carries it ahead
of the confirm phase. A value captured once at first render therefore falls through to *now* and is never
revisited, silently defaulting every real join to now — the bug this requirement exists to forbid.

The surface SHALL therefore remember only the user's **choice** — the selected option (Now / Event start
/ Custom) and, for Custom, the picked local wall-clock value — never a **default instant** seeded at
mount, and SHALL derive the resulting instant from the phase on every composition. The Custom value SHALL
be coerced up to the floor (the event's start) on every composition, so the surface never displays or
commits a cutoff below the floor. This makes the staleness above unrepresentable rather than merely
guarded: there is no default captured, so there is nothing to go stale, and no re-seed can discard a
choice the user made.

The **commit phases** (committing, commit-failed) SHALL carry `startsAt` for this reason. A retry commits
**without** passing back through the loaded phase, so a surface that could read the start only from the
loaded phase would derive a retry's cutoff from *now* — silently discarding the user's selection at the
one moment they are already recovering from a failure. A surface entered directly at a phase that carries
no start date SHALL fall back to *now* — the safe direction, since *now* shares too few photos (which a
re-join fixes) whereas the opposite error cannot be undone.

#### Scenario: The cutoff shows the event's start across the real phase sequence
- **WHEN** the join surface advances loading → explain-access → confirm, for an event with a `startsAt`
- **THEN** the capture-date value shows the event's start, not the current time

#### Scenario: A user's chosen cutoff survives a failed commit
- **WHEN** the user picks an option (including a Custom instant), confirms, the commit fails, and they retry
- **THEN** the retry carries the cutoff their choice resolves to against the event's start — not now, and
  not a value re-derived from a phase that lost it

#### Scenario: A surface with no start date falls back to now
- **WHEN** the join surface renders a phase that carries no `startsAt`, and none was ever loaded
- **THEN** the capture-date is the current time, never absent and never whole-library

### Requirement: Switching events composes leave then join

The system SHALL, when a deeplink for a **different** event is decoded while an event is already
configured, present a leave-style confirmation (of the form "Switch events?") carried as a
`pendingSwitch` on the `Joined` state, and this confirmation SHALL be details-gated like a first join
(it shows the new event's fetched name and blocks on a 404). Its body SHALL name **both** the current
and the new event and SHALL state the participation the switch resets to (the member shares the photos
they take and receives everyone's), so the reset defaults (direction `Both`, cutoff = the new event's
start, album off) are not a surprise. On confirm, the system SHALL run **`leaveEvent.leave()` then
`joinEvent.join(newEventId)`** — composing the existing `LeaveEvent` use-case without modifying it, so
the switch inherits any backend behavior `leave()` later gains. If the leave succeeds but the join then
fails, the device is transiently in **no event**; the surface SHALL show an error and a **Retry** that
re-runs **only** the join for the remembered target.

#### Scenario: Switch confirmation names both events and states the reset, then composes leave then join
- **WHEN** a link for a different event is decoded while joined, its details load, and the user confirms the switch
- **THEN** the confirmation body named both events and stated that the switch shares the member's photos and receives everyone's, and `leaveEvent.leave()` runs first and then `joinEvent.join(newEventId)`, leaving the device joined to the new event

#### Scenario: A join that fails after a successful leave is retryable
- **WHEN** the leave step succeeds but the subsequent join enrollment fails
- **THEN** the device is in no event and the surface offers a Retry that re-runs only the join for the pending target

#### Scenario: The switch never edits the leave use-case
- **WHEN** the switch path runs
- **THEN** it invokes `LeaveEvent.leave()` as-is (the composition adds no leave behavior of its own)
