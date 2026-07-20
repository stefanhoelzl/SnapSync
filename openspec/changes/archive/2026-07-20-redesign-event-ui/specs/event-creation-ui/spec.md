## MODIFIED Requirements

### Requirement: Create-event screen

When the UI state is the create layer, the status screen SHALL render a create-event screen composed
from `App*` components: a **host-framed hero** (a HOST AN EVENT eyebrow, the drawn app-mark badge, a
"Start an event" title, and one warm line), a **question heading** ("What's it called?") over an
`AppTextField` for the event name, an **event start-date section** beneath it that states the start's
consequence, a `PrimaryButton` labelled to create the event, and a passive hint that an event can also
be joined by scanning its QR code with the Camera. The Create action SHALL be disabled while the trimmed
name is empty, and the field SHALL accept at most 100 characters. While the UI state is `CreatingEvent`,
the screen SHALL show a preparing indicator where the form was, keeping the **identical** host hero in
the same place so the surface reads as the form settling rather than a new screen (no layout jump). The
create layer SHALL NOT show the leave action.

The **start-date section** SHALL render the currently-chosen start as a readable label with an **edit
affordance**, opening the design system's date/time picker (capability `design-system`), together with a
stated-consequence **note** that this start is the earliest cutoff any guest can pick — "Only photos
taken after this time are shared" (capability `photo-selection-policy`). It SHALL default to **now**, and
that default SHALL be **frozen at the moment the screen first composes** — it SHALL NOT be re-derived at
submit. The label is the screen's whole statement about what will be sent, so a value that silently
drifted between being displayed and being posted would make the screen lie. A start-date section SHALL
always carry a value; there is no unset state.

The start-date picker SHALL impose **no bounds**: a start may be chosen arbitrarily far in the past
**or** in the future. A future start is a supported case (creating an event ahead of time), and an early
start is how a host brings pre-existing photos into scope — including how a developer creates an event
whose contents reach back to a seeded, distant-past library.

#### Scenario: The host hero frames the create surface
- **WHEN** the create screen is shown
- **THEN** it leads with the HOST AN EVENT eyebrow, the app-mark badge, the "Start an event" title, and one warm line, above the name question

#### Scenario: The start section defaults to now, frozen at composition, and states its consequence
- **WHEN** the create screen first composes at `18:04` and the user then spends ten minutes typing a name
- **THEN** the start section still reads `18:04` (and `18:04` is the value posted, not the instant Create was tapped), beside a note that only photos after this time are shared

#### Scenario: Editing the start opens the picker and updates the label
- **WHEN** the user activates the start section's edit affordance and picks a date and time
- **THEN** the picker closes and the section's label shows the newly-picked start

#### Scenario: The start is unbounded in both directions
- **WHEN** the user picks a start years in the past, or one in the future
- **THEN** the picker accepts it and the section shows it, no bound being imposed

#### Scenario: Empty name disables Create
- **WHEN** the create screen is shown and the name field is empty or whitespace-only
- **THEN** the Create action is disabled

#### Scenario: A typed name enables Create
- **WHEN** the user types a non-empty name
- **THEN** the Create action is enabled and activating it invokes `EventCreator.create` with the
  trimmed name **and the chosen start date** through the container

#### Scenario: The name field caps at 100 characters
- **WHEN** the user attempts to enter more than 100 characters
- **THEN** the field holds at 100 characters

#### Scenario: The scan hint is present and passive
- **WHEN** the create screen is shown
- **THEN** it displays a passive "scan a QR to join" hint with no button

#### Scenario: Creating keeps the host hero and shows a preparing indicator
- **WHEN** the UI state is `CreatingEvent`
- **THEN** the screen keeps the identical host hero in place and shows a preparing indicator where the input was

### Requirement: Create screen owns the event-link intent and one inline error surface

The container SHALL expose an `onCreateEvent(name: String, startsAt: LocalDateTime)` intent that converts
the picked **local** date-time into the canonical cutoff string via the injected time source (capability
`photo-selection-policy` — the same `CutoffFormatter` the join surface already uses, so there is exactly one
origin of "now" and one local→UTC conversion in the app) and calls `EventCreator.create`. The container
SHALL retain the `onOpenUrl(raw: String)` intent that decodes an incoming event link via the
`event-link` decoder and, on success, provisions it (the QR-join path is unchanged).

The create screen SHALL render a single error surface as an **error banner above the Create action** —
never as a reddened name field. The name field is client-guarded on both knowable rules (empty → Create
disabled; over-length → the field caps at 100), so a returned failure is a **submission** failure, not
the current name being malformed; reddening the field would falsely blame the host's typing. The one
banner serves two causes: a `Failed(reason)` create error (sticky until the next create attempt) and a
transient, self-clearing invalid-link error surfaced when `onOpenUrl` receives a URL the decoder rejects
— exposed as the container host's own presentation-owned `transientError` read-model `StateFlow` (the
set-then-clear choreography lives in presentation; the untested shell renders the value verbatim and
decides nothing — spec `module-architecture`, "Commands cross one door": interaction state is
presentation-owned). An invalid link MUST NOT change persisted config.

#### Scenario: The container converts the local pick to the canonical shape
- **WHEN** `onCreateEvent` receives a local date-time from the screen
- **THEN** it converts it through the injected time source into `yyyy-MM-dd'T'HH:mm:ss'Z'` and passes
  that string to `EventCreator.create`, the screen never handling a cutoff string itself

#### Scenario: Create failure shows a sticky banner above the action, not a red field
- **WHEN** a create attempt fails and the reduction returns to the create-input state
- **THEN** the failure copy shows in the error banner above Create (not on the name field) and persists
  until the next create attempt (which re-enters `InFlight` and clears it)

#### Scenario: Invalid link flashes a transient banner and changes nothing
- **WHEN** `onOpenUrl` receives a URL the decoder rejects
- **THEN** a transient invalid-link error is surfaced in the same banner, persisted config is
  unchanged, and the error self-clears

#### Scenario: A valid event link still joins from the create screen
- **WHEN** `onOpenUrl` receives a structurally-valid `https://<link domain>/join#…` URL while the create screen is shown
- **THEN** the decoded config is provisioned (saved) and the existing join flow runs
