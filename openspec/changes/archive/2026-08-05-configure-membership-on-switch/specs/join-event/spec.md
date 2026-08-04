## MODIFIED Requirements

### Requirement: Enrollment fires only on a genuine new join

The enrollment PUT (which writes an **empty** manifest) SHALL fire **only** when joining an event the
device is not currently in — which, since a switch's leave clears the config before its join commits, is
always a join taken with **no config**. A re-scan or re-provision of the event the device is **already**
joined to SHALL be a no-op that does **not** re-write the empty manifest, so a real asset manifest
previously written by the upload cycle is never clobbered back to empty. This is consistent with
`event-rejoin-reconciliation`'s no-op on re-provision of the already-joined event.

#### Scenario: Re-scanning the current event does not clobber the manifest
- **WHEN** a deeplink for the event the device is already joined to is decoded
- **THEN** no enrollment PUT is issued and the device's existing manifest is left untouched

#### Scenario: Switching to a different event enrolls
- **WHEN** a switch's leave has cleared the config and the member confirms the join for the new event
- **THEN** the enrollment PUT for the new event is issued as part of that join

### Requirement: Switching events composes leave then join

The system SHALL, when a deeplink for a **different** event is decoded while an event is already
configured, present a leave-style confirmation (of the form "Switch events?") carried as a
`pendingSwitch` on the `Joined` state, and this confirmation SHALL be details-gated like a first join
(it shows the new event's fetched name and blocks on a 404). Its body SHALL name **both** the current
and the new event, and SHALL state **nothing** about the participation that results: the member chooses
the direction, the capture-date range, and the album opt-in on the join surface that follows, so a body
promising a fixed participation would be false. For the same reason the confirmation SHALL NOT render a
shareable count (capability `join-share-count`) — no range has been chosen for it to count.

Its confirm SHALL run the **leave and nothing else** — `LeaveEvent.leave()` as-is, reached through the
same leave command the joined layer's Leave action uses, so in-flight downloads are cancelled and
non-terminal download rows pruned first, and so the switch inherits any backend behavior `leave()` later
gains. The confirm SHALL NOT commit a join.

The pending join SHALL survive the leave. Once the leave has cleared the config, the system SHALL
present the **regular full-screen join surface** for the new event — the same surface a first join
presents, with the Share and Receive switches, the capture-date cutoff choices, the album opt-in, the
live shareable count, and the photo-access explainer where the gate's loaded-phase derivation calls for
it — and the confirm there SHALL commit the join with the member's chosen direction, range, and album
opt-in like any other join. A commit failure SHALL therefore be the join surface's ordinary
commit-failure phase with its Retry, not a switch-specific error.

Because the leave has already run when that surface is presented, **cancelling it SHALL leave the device
in no event**, returning to the create layer per the surface's existing cancel rule; rescanning an invite
rejoins. A leave whose local config clear fails SHALL leave the confirmation presented (the config is
still there, so the state is unchanged), no error surface of its own being raised — matching the joined
layer's Leave action, which reports a failed clear no differently.

#### Scenario: The switch confirmation names both events and promises no participation
- **WHEN** a link for a different event is decoded while joined and its details load
- **THEN** the confirmation names both the current and the new event, states nothing about the resulting
  participation, and renders no shareable count

#### Scenario: Confirming the switch leaves, then opens the regular join surface
- **WHEN** the user confirms the switch confirmation
- **THEN** `LeaveEvent.leave()` runs (after the leave command's download cancellation and non-terminal row
  prune), no join is committed, and once the config is cleared the pending join is presented as the
  full-screen join surface for the new event

#### Scenario: A switch configures the new membership
- **WHEN** the member, on the join surface reached through a switch, turns the Share switch off, opts into
  the album, and confirms
- **THEN** the join commits with direction `DownloadOnly` and `saveToAlbum = true`, exactly as the same
  choices on a first join would

#### Scenario: A join that fails after a successful leave is retryable
- **WHEN** the leave has run and the subsequent join enrollment fails
- **THEN** the device is in no event and the join surface shows its commit-failure phase, whose Retry
  re-runs only the join for the pending target, carrying the choices the member already made

#### Scenario: Cancelling after the leave leaves the device in no event
- **WHEN** the member cancels on the join surface reached through a switch
- **THEN** the pending join is discarded, no device is enrolled, and the UI returns to the create layer
  with no event configured

#### Scenario: A failed local clear keeps the confirmation presented
- **WHEN** the user confirms the switch and the config clear step fails
- **THEN** the event remains configured, the switch confirmation remains presented so the user can confirm
  again, and no separate error surface is raised

#### Scenario: The switch never edits the leave use-case
- **WHEN** the switch path runs
- **THEN** it invokes `LeaveEvent.leave()` as-is (the composition adds no leave behavior of its own)

### Requirement: The join gate explains photo access before the first system dialog

The join gate SHALL show a full-screen **photo-access explainer** before the photo-library permission
dialog is ever raised, and the system dialog SHALL be reachable from that surface **only** via its
confirm action.

The explainer SHALL be a phase of the `JoiningEvent` family (not a dialog, not a separate `UiState`),
chosen by the gate's **loaded-phase derivation**: the single rule that decides, for loaded event details,
whether the gate presents the **explain-access** phase or the loaded/confirm phase. The derivation SHALL
select the explain-access phase exactly when **both** hold:

- **no event is currently configured** (so a switch's confirmation, presented over the joined layer while
  the old event is still configured, is never the explainer), and
- photo permission is `NOT_DETERMINED`.

The derivation SHALL run at **every** point the gate resolves to a loaded phase, so that no entry path
can reach the confirm surface without having been offered the explainer. There are two such points: when
the details fetch resolves, and when a switch's leave clears the config (capability `join-event`,
"Switching events composes leave then join"), the second re-deriving from the details the first already
loaded rather than re-fetching them. The derivation SHALL run only after the config is confirmed cleared,
so a leave whose clear failed leaves the phase untouched.

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

#### Scenario: A switch does not explain before its leave
- **WHEN** a deeplink for a different event is decoded while an event is already configured, and photo permission is `NOT_DETERMINED`
- **THEN** the derivation selects the loaded/confirm phase, so the switch confirmation is presented over the joined layer and the explain-access phase is not entered

#### Scenario: A switch explains after its leave
- **WHEN** the user confirms that switch confirmation and the leave clears the config, photo permission still being `NOT_DETERMINED`
- **THEN** the derivation runs again over the already-loaded details and the gate enters the explain-access phase, naming the new event, before its confirm surface

#### Scenario: A created event's first join explains too
- **WHEN** a freshly created event is routed into the pending-join gate, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the same explain-access phase is entered, naming the just-created event, with no create-specific surface

#### Scenario: The explainer never auto-requests
- **WHEN** the explain-access phase is rendered
- **THEN** no permission request is made until the user takes the confirm action

### Requirement: The join surface shows a live count of the photos that will be shared

The join surface's **loaded** phase SHALL present, within the Share section and beneath the Share switch,
a **live count** of how many of the device's own photos the currently-chosen cutoff would share to the
event, sourced from the shareable-count read-model (capability `join-share-count`). The count SHALL be
recomputed whenever the chosen cutoff changes (Now / Event start / Custom, or a Custom instant) and
whenever the Share switch is toggled, so the number always reflects the pending choice.

- When the count is a positive number `XX`, the row SHALL read `XX photos from your gallery will be
  shared`.
- When the count is **zero** — the legitimate result of the **Now** cutoff, or of no in-scope photos —
  the row SHALL read `0 photos from your gallery will be shared` together with a forward gloss `New photos
  you take will be shared as you go`, so a true zero does not read as a failure.
- While the count is (re)computing, the row SHALL show a brief `counting…` state in place of the number,
  resolving to the number when the computation settles.
- When the Share switch is **off**, the row SHALL be hidden (the Share section already hides the cutoff),
  since a member sharing nothing has no count.
- When the photo-access grant does not permit a count (`DENIED`, or unresolved `NOT_DETERMINED` —
  capability `join-share-count`), the row SHALL be omitted rather than showing a spinner that cannot
  resolve.

The count is rendered on this surface alone. A switch reaches this same surface after its leave, so it
inherits the count against the range the member actually chooses; the switch confirmation that precedes
the leave renders none.

The count is **decision-support on a decision surface** and does not change what confirming does: it
informs the cutoff choice, and confirming still crosses the chosen cutoff and derived direction to
`JoinEvent` unchanged.

#### Scenario: The loaded phase shows the count under the Share switch
- **WHEN** the `JoiningEvent` loaded phase renders with the Share switch on and the cutoff at Event start,
  and photo access permits a count
- **THEN** a row beneath the Share switch reads `XX photos from your gallery will be shared` for the
  count the chosen cutoff admits

#### Scenario: Changing the cutoff updates the count
- **WHEN** the user changes the cutoff choice from Event start to a Custom date reaching further back
- **THEN** the count recomputes and the row shows the new number (briefly showing `counting…` while it
  recomputes)

#### Scenario: A zero count carries the forward gloss
- **WHEN** the chosen cutoff is Now (or no photo is in scope), so the count is zero
- **THEN** the row reads `0 photos from your gallery will be shared` and `New photos you take will be
  shared as you go`

#### Scenario: Turning Share off hides the count
- **WHEN** the user turns the Share switch off
- **THEN** the count row is hidden along with the cutoff choices

#### Scenario: Without a usable photo grant no count is shown
- **WHEN** the loaded phase renders while photo access is `DENIED` or still unresolved
- **THEN** no count row is shown, and no library read is attempted for it

#### Scenario: A switch's count arrives on the join surface, not the confirmation
- **WHEN** a switch confirmation is presented for a different event and photo access permits a count
- **THEN** the confirmation renders no count, and the join surface reached after its leave renders the row
  for the member's chosen range like any other join
