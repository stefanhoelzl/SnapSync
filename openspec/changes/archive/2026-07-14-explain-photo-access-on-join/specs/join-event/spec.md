## ADDED Requirements

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

The explainer's **confirm** ("I understand") SHALL call `PermissionRequester.request()` and advance to
the loaded/confirm phase in the same action. Because `request()` returns nothing and cannot suspend
(capability `permission-gate`), the phase SHALL advance immediately rather than await an outcome; the
system dialog lands over the confirm surface. The explainer's **cancel** SHALL discard the pending join
exactly as cancel does in every other phase — no enrollment, no config, no producer.

The explainer SHALL NOT auto-request: the system dialog fires only from the confirm action (CTA-only
priming). No other join-gate phase SHALL raise it.

The copy SHALL state that photos the user takes are shared automatically with everyone in the event,
SHALL state that the permission is also what saves other members' photos into the user's library, and
SHALL state that only photos taken after the capture-date the user chooses next are shared. It MUST NOT
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
- **THEN** `PermissionRequester.request()` is called exactly once and the gate advances to the loaded/confirm phase carrying the same event name and default cutoff

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
- **THEN** the same explain-access phase is entered, with no create-specific surface

#### Scenario: The explainer never auto-requests
- **WHEN** the explain-access phase is rendered
- **THEN** no permission request is made until the user takes the confirm action

### Requirement: The cutoff row is seeded from the loaded default, not from the mount-time phase

The join surface's capture-date row SHALL be prefilled from the **loaded** `createdAt` default, and the
seed SHALL be taken from the first phase that carries one — **not** from whichever phase the surface
happened to first render.

The surface is mounted at the **loading** phase (the pending join is created before the details fetch is
issued), so no default exists at first render; the explain-access phase, when shown, carries the default
ahead of the confirm phase. A seed evaluated once at first render therefore falls through to *now* and is
never revisited, silently defaulting every real join to now and defeating "a prefilled cutoff value
(defaulting to the loaded `createdAt`)".

The seed SHALL be taken **once**. The commit phases (committing, commit-failed) carry no default, so a
re-seed on every phase change would discard a value the user had chosen, which a retry must reuse. A
surface entered directly at a phase that never carries a default SHALL keep *now* — the safe direction,
since *now* shares too few photos (which a re-join fixes) whereas the opposite error cannot be undone.

#### Scenario: The cutoff is seeded from the loaded default across the real phase sequence
- **WHEN** the join surface advances loading → explain-access → confirm, with a loaded `createdAt` default
- **THEN** the capture-date row shows the loaded `createdAt`, not the current time

#### Scenario: A user's chosen cutoff survives a failed commit
- **WHEN** the user changes the capture-date, confirms, the commit fails, and they retry
- **THEN** the retry carries the cutoff the user chose, not a re-seeded default and not now

#### Scenario: A surface with no loaded default keeps now
- **WHEN** the join surface renders a phase that carries no `createdAt` default, and none was ever loaded
- **THEN** the capture-date is the current time, never absent and never whole-library
