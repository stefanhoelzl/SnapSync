## MODIFIED Requirements

### Requirement: Create screen owns the event-link intent and one inline error surface

The container SHALL expose an `onCreateEvent(name: String, startsAt: LocalDateTime)` intent that converts
the picked **local** date-time into the canonical cutoff string via the injected time source (capability
`photo-selection-policy` — the same time source the join surface already uses, so there is exactly one
origin of "now" and one local→UTC conversion in the app) and calls `EventCreator.create`. The container
SHALL retain the `onOpenUrl(raw: String)` intent that decodes an incoming event link via the
`event-link` decoder and, on success, provisions it (the QR-join path is unchanged).

The create screen SHALL render a single error surface as an **error banner above the Create action** —
never as a reddened name field. The name field is client-guarded on both knowable rules (empty → Create
disabled; over-length → the field caps at 100), so a returned failure is a
**submission** failure, not the current name being malformed; reddening the field would falsely blame the
host's typing.

The one banner serves two causes: a `Failed(reason)` create error (sticky until the next create attempt)
and a transient, self-clearing invalid-link error surfaced when `onOpenUrl` receives a URL the decoder
rejects. Because the screen renders **one** surface, the create state SHALL carry **one** inline error
value, and the reduction SHALL resolve the two causes into it with the **transient winning** while it is
showing. The transient error SHALL NOT be supplied to the screen beside the state: a second value for the
same banner is a value a call site can omit without any failure, and one already was.

The set-then-clear choreography SHALL remain presentation-owned (spec `module-architecture`, "Commands
cross one door": interaction state is presentation-owned, and it dies with the UI). The untested shell
renders the state verbatim and decides nothing. An invalid link MUST NOT change persisted config.

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
- **THEN** the create state's inline error carries the transient invalid-link copy, persisted config is
  unchanged, and the error self-clears

#### Scenario: The transient error outranks a sticky create failure
- **WHEN** a create attempt has failed, its copy is showing, and an invalid link then arrives
- **THEN** the create state's inline error carries the transient copy until it self-clears, after which
  the sticky create failure shows again

#### Scenario: A host cannot render the create layer without its error
- **WHEN** any host composes the create layer from the state
- **THEN** the inline error is present whenever there is one, because it travels inside the create state
  rather than as a separate parameter

#### Scenario: A valid event link still joins from the create screen
- **WHEN** `onOpenUrl` receives a structurally-valid `https://<link domain>/join#…` URL while the create screen is shown
- **THEN** the decoded config is provisioned (saved) and the existing join flow runs
