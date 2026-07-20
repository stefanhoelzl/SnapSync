# join-event — delta

## MODIFIED Requirements

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

`GRANTED`, `LIMITED`, and `DENIED` SHALL all go straight to the loaded/confirm phase. `LIMITED` is a
usable grant (capability `limited-photo-access`) — an explainer over it would explain a dialog that has
already been answered. `DENIED` is excluded because iOS raises the photo dialog at most once: from
`DENIED` a request is a silent no-op, so an explainer promising a dialog that cannot appear would be
false. A `DENIED` joiner instead meets the joined layer's existing `NeedsAccess` Settings affordance
(capability `sync-status-screen`).

The explainer's **confirm** ("I understand") SHALL call `PhotoAccessRequester.request()` and advance to
the loaded/confirm phase in the same action. Because `request()` returns nothing and cannot suspend
(capability `permission-gate`), the phase SHALL advance immediately rather than await an outcome; the
system dialog lands over the confirm surface. The explainer's **cancel** SHALL discard the pending join
exactly as cancel does in every other phase — no enrollment, no config, no producer.

The explainer SHALL NOT auto-request: the system dialog fires only from the confirm action (CTA-only
priming). No other join-gate phase SHALL raise it.

The copy SHALL state that photos the user takes are shared automatically with everyone in the event,
SHALL state that the permission is also what saves other members' photos into the user's library, and
SHALL state that only photos taken after the capture-date the user chooses next are shared. It SHALL
present the system dialog's outcomes as a real choice: allowing **all** photos shares automatically,
while choosing **specific photos** shares only what the user picks (and they can add more anytime) —
limited access is presented as a first-class option, not a degraded one. It MUST NOT
describe the app's function as "backing up" the user's library (consistent with `event-creation-ui`),
and MUST NOT claim a scope wider than the membership can produce.

Because a created event routes into the **same** gate (`onEventCreated` → the pending-join state), the
explainer SHALL cover a first-time creator and a first-time scanner identically, with no create-path
surface of its own.

#### Scenario: A first join with permission never asked explains before the dialog
- **WHEN** the details fetch resolves to a loaded event, no event is configured, and photo permission is `NOT_DETERMINED`
- **THEN** the gate enters the explain-access phase, no permission request has been made, and the loaded/confirm surface is not yet shown

#### Scenario: The confirm requests permission and advances to the confirm surface
- **WHEN** the user takes the explainer's confirm action
- **THEN** `PhotoAccessRequester.request()` is called exactly once and the gate advances to the loaded/confirm phase carrying the same event name and default cutoff

#### Scenario: Cancelling the explainer abandons the join
- **WHEN** the user cancels on the explain-access phase
- **THEN** the pending join is discarded, no device is enrolled, no config is saved, no upload producer is enabled, and the UI returns to the create layer

#### Scenario: Already-granted access skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `GRANTED`
- **THEN** the gate enters the loaded/confirm phase directly and no explainer is shown

#### Scenario: A limited grant skips the explainer
- **WHEN** the details fetch resolves to a loaded event and photo permission is `LIMITED`
- **THEN** the gate enters the loaded/confirm phase directly — the grant exists; there is no dialog to
  explain
