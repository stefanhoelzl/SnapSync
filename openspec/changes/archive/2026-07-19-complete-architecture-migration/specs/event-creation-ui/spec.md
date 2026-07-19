# event-creation-ui — delta for complete-architecture-migration

## MODIFIED Requirements

### Requirement: Create screen owns the event-link intent and one inline error surface
The container SHALL expose an `onCreateEvent(name: String, startsAt: LocalDateTime)` intent that converts
the picked **local** date-time into the canonical cutoff string via the injected time source (capability
`photo-selection-policy` — the same `CutoffFormatter` the join surface already uses, so there is exactly one
origin of "now" and one local→UTC conversion in the app) and calls `EventCreator.create`. The container
SHALL retain the `onOpenUrl(raw: String)` intent that decodes an incoming event link via the
`event-link` decoder and, on success, provisions it (the QR-join path is unchanged). The create
screen SHALL render a single inline error region serving two causes: a `Failed(reason)` create error
(sticky until the next create attempt) and a transient, self-clearing invalid-link error surfaced
when `onOpenUrl` receives a URL the decoder rejects — exposed as the container host's own
presentation-owned `transientError` read-model `StateFlow` (the set-then-clear choreography lives
in presentation; the untested shell renders the value verbatim and decides nothing — spec
`module-architecture`, "Commands cross one door": interaction state is presentation-owned). An
invalid link MUST NOT change persisted config.

#### Scenario: The container converts the local pick to the canonical shape
- **WHEN** `onCreateEvent` receives a local date-time from the screen
- **THEN** it converts it through the injected time source into `yyyy-MM-dd'T'HH:mm:ss'Z'` and passes
  that string to `EventCreator.create`, the screen never handling a cutoff string itself

#### Scenario: Create failure shows a sticky inline error
- **WHEN** a create attempt fails and the reduction returns to the create-input state
- **THEN** the inline error shows the failure copy and persists until the next create attempt (which
  re-enters `InFlight` and clears it)

#### Scenario: Invalid link flashes a transient error and changes nothing
- **WHEN** `onOpenUrl` receives a URL the decoder rejects
- **THEN** a transient invalid-link error is surfaced on the create screen, persisted config is
  unchanged, and the error self-clears

#### Scenario: A valid event link still joins from the create screen
- **WHEN** `onOpenUrl` receives a structurally-valid `https://<link domain>/join#…` URL while the create screen is shown
- **THEN** the decoded config is provisioned (saved) and the existing join flow runs
