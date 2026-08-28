# event-rename Specification

## Purpose

**Fix the event's name, for everyone.** An event is named once at creation and, until now, never again:
a host who mistyped it — or named an event before it took its real shape — was stuck with the wrong name
on every member's status screen and on the web download page for the event's whole lifetime.

The name is the one marker field whose immutability bought nothing. The write-once rule on the event
marker (`api-endpoints`) names its threats precisely: a mutation route would let anyone holding the
event id "retroactively widen every future joiner's default scope — or extend an event's own limits". A
name does neither. It is cosmetic to the upload gate, cosmetic to the extension, and load-bearing for
display alone — so it, and only it, became writable.

Any joined member may rename, and no owner concept is introduced: possession of the event id already
grants uploading into the event and downloading every photo in it, so renaming is strictly weaker than
what a holder already has. Other members receive the new name through the membership refresh that
already runs on every foreground — the rename adds no propagation mechanism of its own.

The rename is deliberately **never destructive**. A `404` from the rename route is a *single* witness
that the event is gone, and the self-leave (`leave-event`) requires two independent witnesses, one of
them offline, so that no backend fault can destroy every membership at once. That `404` is therefore
collapsed into the generic failure and given no user-facing meaning, because a surfaced meaning invites a
future change to act on it.

Decision record: `changes/archive/2026-08-04-add-event-rename`,
`changes/archive/2026-08-28-make-the-screen-a-function-of-state` (the failure copy is formatted in the reduction).

## Requirements

### Requirement: A joined member renames the event for everyone

The system SHALL let any joined member change the event's name, and the changed name SHALL become the
event's name for **every** member. The rename SHALL NOT be a per-device display label: it is written to
the backend marker, and other members receive it through the existing membership refresh (capability
`join-event`), not through any new propagation mechanism.

Renaming SHALL require no ownership, role, or creator match. Possession of the event id already grants
uploading into the event and downloading every photo in it, so a rename is strictly weaker than what a
holder already has; the backend route's device-token gate (capability `api-endpoints`) is the only
authorization.

The rename SHALL change **nothing else**: not the `eventId`, the capture-date range, the direction, the
album opt-in, the ledger, the enrollment, the device identity, or the invite link (which carries the
event id only, capability `event-link`).

#### Scenario: A rename by a non-creating member is accepted
- **WHEN** a member who did not create the event renames it and the backend accepts the change
- **THEN** the new name is stored on the marker and no ownership check refuses the request

#### Scenario: A rename leaves every other membership field untouched
- **WHEN** a membership with a chosen cutoff, direction, and album opt-in is renamed
- **THEN** the persisted `EventConfig` differs from the previous one in `name` alone

#### Scenario: Other members receive the new name on their next foreground
- **WHEN** one member renames the event and another member's device next foregrounds
- **THEN** that device's membership refresh fetches the details and persists the new name, exactly as it
  does for any other changed name

### Requirement: The rename port reports three outcomes

The capability SHALL provide an `EventRename` port (`:domain` `ports/`) whose single operation submits a
new name for an event id and returns a sealed `RenameOutcome` with exactly three shapes:

- `Renamed(name)` — the backend accepted the change and reported the stored name.
- `InvalidName` — the backend rejected the name (`400`).
- `Transient` — every other outcome: a non-2xx other than `400`, a transport failure, or a parse failure.

A `404` SHALL map to `Transient`. It SHALL NOT be given a distinct outcome or distinct user-facing copy.
A `404` from this route is a **single** witness that the event is gone, and the self-leave (capability
`leave-event`) deliberately requires two independent witnesses, one of them offline, so that no backend
fault can destroy every membership at once. Surfacing the single witness here would create a second route
to that destructive outcome; the teardown SHALL remain the membership refresh's two-witness path alone.

The port SHALL be non-throwing: a transport or parse error maps to `Transient`, never an exception.

#### Scenario: A 400 is distinguishable from other failures
- **WHEN** the backend responds `400` to a rename
- **THEN** the outcome is `InvalidName`, and the screen shows invalid-name copy rather than generic
  failure copy

#### Scenario: A 404 maps to the transient outcome
- **WHEN** the backend responds `404` to a rename because the event has been swept
- **THEN** the outcome is `Transient` and no distinct event-gone outcome exists

#### Scenario: A transport failure does not throw
- **WHEN** the rename request fails at the transport layer
- **THEN** the outcome is `Transient` and no exception reaches the caller

### Requirement: A rename never tears down a membership

No rename outcome SHALL clear the membership config, notify the backend of a leave, cancel downloads, or
otherwise perform any part of the leave teardown (capability `leave-event`). A failed rename SHALL leave
the persisted `EventConfig` byte-identical to what it was before the attempt.

#### Scenario: A 404 rename leaves the membership intact
- **WHEN** a rename returns `404` and therefore `Transient`
- **THEN** the membership config is unchanged, no leave is notified, and the member remains joined

#### Scenario: A failed rename persists nothing
- **WHEN** a rename fails for any reason
- **THEN** no `ConfigStore.save` occurs

### Requirement: The rename use-case is a guarded whole-object writer of the membership config

The capability SHALL provide a `RenameEvent` use-case in `:domain` `feature/membership` — a writer of
that feature's one-writer membership config, alongside join/provision, leave, the membership refresh,
and `ReconfigureEvent`. It SHALL follow the same discipline: read the current `EventConfig`, **guard
that its `eventId` still matches** the event being renamed, and on success save the **whole** object with
only `name` replaced.

The persisted name SHALL be the value the backend **echoed** in its response, not the value the member
typed. The backend trims, so echoing is what guarantees the client and the marker never disagree about
whitespace.

When no config is present, or the current config's `eventId` differs from the one being renamed (a result
landing after a switch or a leave), the use-case SHALL persist nothing.

#### Scenario: A successful rename saves the whole config with only the name replaced
- **WHEN** the backend echoes `"Ana's 30th"` for the currently joined event
- **THEN** exactly one whole-object `EventConfig` save occurs, carrying the echoed name and every other
  field unchanged

#### Scenario: The echoed name wins over the typed name
- **WHEN** the member submits `"  Ana's 30th  "` and the backend echoes the trimmed `"Ana's 30th"`
- **THEN** the persisted name is `"Ana's 30th"`

#### Scenario: A result for a no-longer-current event persists nothing
- **WHEN** a rename result arrives for an event id that no longer matches the persisted config
- **THEN** no save occurs and the current membership is untouched

#### Scenario: A rename with no membership persists nothing
- **WHEN** a rename result arrives while no event is configured
- **THEN** no save occurs

### Requirement: The rename lifecycle is its own status seam with a success value

The capability SHALL expose a `RenameStatus` seam (a `StateFlow` the presentation reduction consumes and
carries as a **field of the joined state**, not as a `UiState` family and not as a health rung) with
exactly four shapes:

- `Idle` — no rename in flight.
- `InFlight` — the request is running.
- `Succeeded` — the rename completed and the new name is persisted.
- `Failed(reason)` — the request failed, with `reason` one of `INVALID_NAME` (the backend's `400`) or
  `SERVER` (every other failure).

`Succeeded` SHALL exist even though the create-status twin (capability `event-creation-ui`) deliberately
has no success value. That twin needs none because a successful create provisions config and moves the
reduction off the create layer entirely; a successful rename leaves the member on the same screen with a
dialog that must close, so the success transition SHALL be an observable fact rather than an inference
from a return to `Idle`.

The capability SHALL provide a command that resets the seam to `Idle`, which the screen fires after
consuming `Succeeded` or dismissing a `Failed`.

The seam SHALL keep reporting a **reason**; turning that reason into words SHALL happen in the
presentation reduction, not in the screen. The joined state therefore carries the dialog's condition with
its failure **copy already formatted**, exactly as the create layer's twin does (capability
`event-creation-ui`) — having one of a matched pair formatted in the reduction and the other in a
composable was an inconsistency, not a design.

#### Scenario: A successful rename is observable as a success
- **WHEN** a rename completes successfully
- **THEN** the seam emits `InFlight` and then `Succeeded`, and the screen has an explicit success to
  react to

#### Scenario: A rejected name is distinguishable from a server failure
- **WHEN** the backend rejects the name with `400`
- **THEN** the seam emits `Failed(INVALID_NAME)`

#### Scenario: Reset returns the seam to Idle
- **WHEN** the screen consumes a terminal status and fires the reset command
- **THEN** the seam emits `Idle` and a subsequent rename starts from a clean sequence

#### Scenario: The rename status travels with the state
- **WHEN** a rename is in flight and the joined state is inspected
- **THEN** the state carries the in-flight condition, and the screen receives it through that state rather
  than as a separate parameter

#### Scenario: The failure copy is formatted where the create layer's is
- **WHEN** a rename fails with either reason
- **THEN** the joined state carries the message the dialog shows, and the screen renders it verbatim —
  the reason itself never reaches the screen

### Requirement: The rename affordance opens a pre-filled text-prompt dialog

Tapping the joined layer's rename affordance (capability `sync-status-screen`) SHALL open a text-prompt
dialog (capability `design-system`) **pre-filled with the event's current name**, carrying a confirm and a
cancel action.

The dialog SHALL guard the name the same way the create screen does (capability `event-creation-ui`): the
field SHALL accept at most 100 characters, and confirm SHALL be disabled while the trimmed value is empty
**or unchanged from the current name**, so a no-op rename never reaches the network. The trimmed value
SHALL be submitted.

While `InFlight` the dialog SHALL remain open and indicate that the request is running. On `Succeeded` it
SHALL close. On `Failed` it SHALL remain open, keep the typed value, and show the failure as an **error
banner** — never as a reddened name field, for the same reason the create screen states: a server
rejection must not falsely blame the member's typing.

Cancelling SHALL close the dialog, submit nothing, and leave the name unchanged.

#### Scenario: The dialog opens pre-filled
- **WHEN** the member taps the rename affordance on an event named `"Weekend"`
- **THEN** the dialog opens with `"Weekend"` already in the field

#### Scenario: Confirm is disabled for an unchanged name
- **WHEN** the dialog is open and the trimmed field value equals the current name
- **THEN** confirm is disabled and no request can be made

#### Scenario: Confirm is disabled for an empty name
- **WHEN** the member clears the field or leaves only whitespace
- **THEN** confirm is disabled

#### Scenario: The field caps at 100 characters
- **WHEN** the member types more than 100 characters
- **THEN** the field accepts only the first 100

#### Scenario: A failure keeps the dialog open with an error banner
- **WHEN** the rename fails
- **THEN** the dialog stays open with the typed value intact and the failure shown in an error banner
  above confirm, not on the name field

#### Scenario: Success closes the dialog
- **WHEN** the rename succeeds
- **THEN** the dialog closes and the heading shows the new name

#### Scenario: Cancel submits nothing
- **WHEN** the member edits the field and cancels
- **THEN** no request is made and the event name is unchanged
