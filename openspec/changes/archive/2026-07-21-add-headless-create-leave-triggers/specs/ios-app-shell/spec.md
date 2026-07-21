## ADDED Requirements

### Requirement: Developer launch-environment CREATE trigger

The iOS app SHALL read a `SNAPSYNC_CREATE_EVENT` variable from the process environment **once per
process launch**. When present, its value SHALL be a `base64url(JSON)` payload decoded by a dedicated,
**strict** `model/` codec (rejecting unknown keys, tested in `commonTest` so it runs on both JVM and
`iosSimulatorArm64`) carrying a **required** `name` and the optional keys `startsAt` (a canonical
`…Z` UTC string; default **now**), `autoJoin` (default `false`), `minPhotoDate`, `direction`, and
`saveToAlbum`. A payload that is absent, not valid `base64url(JSON)`, missing `name`, or carrying an
unknown key SHALL produce **no** side effect.

When the payload is valid the app SHALL mint the event through the **existing attest-gated
`POST /events`** path (the same event-creation client the interactive create uses; it SHALL introduce no
second create path), and SHALL ensure an attestation token is fresh **before** that request so a
cold-launch create is not lost to a not-yet-ready token. Then:

- **without `autoJoin`** — the app SHALL mint the event and join **nothing**, emitting the line
  `created eventId=<uuid>` to the device log (`debug.log`) as the headless oracle for the minted id;
- **with `autoJoin`** — the app SHALL forward a **synthesized** `autoJoin` event link (carrying the
  minted `eventId` plus any supplied `minPhotoDate`/`direction`/`saveToAlbum`) through the existing
  `SnapSyncRoot.onOpenUrl(_:)` / join-gate `autoConfirm` path **verbatim**, landing a membership exactly
  as a confirmed scan would. The chosen `minPhotoDate` SHALL be clamped by the join floor
  (`max(chosen, startsAt)`, capability `photo-selection-policy`) like every other join path — the
  trigger grants it no floor exemption.

The trigger SHALL be **non-idempotent**, and this is its honest contract rather than a defect: because
the backend mints a fresh UUID on every `POST /events`, each cold launch with the variable still set
SHALL mint a **new** event (an `autoJoin` re-launch therefore mints a new event and, being a different
id, leaves any current event first). Operators are expected to **unset** the variable after the mint —
the opposite of the `SNAPSYNC_EVENT_LINK` per-build loop, whose re-application is idempotent.

The trigger SHALL be applied **at most once per process** (not re-applied on Compose view or
view-controller recreation). It SHALL rely on the fact that a process-environment variable is only
injectable via a developer launch; launches from SpringBoard or TestFlight carry no such variable, so
the trigger is inert in production **with no compile-time guard**.

#### Scenario: Mint-only cold launch logs the id and joins nothing
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` set to a valid `base64url(JSON)`
  payload carrying a `name` and **no** `autoJoin`
- **THEN** the app mints the event via `POST /events`, emits `created eventId=<uuid>` to `debug.log`,
  and provisions **no** membership (config stays as it was)

#### Scenario: autoJoin cold launch creates and joins in one launch
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying `autoJoin = true` (optionally
  with `minPhotoDate`/`direction`/`saveToAlbum`)
- **THEN** the app mints the event and, forwarding a synthesized `autoJoin` link through the existing
  `onOpenUrl`/`autoConfirm` path, enrolls and provisions that membership with the chosen cutoff clamped
  to the join floor — no user interaction

#### Scenario: A subsequent cold launch mints a second event
- **WHEN** the app is cold-launched again in a fresh process with `SNAPSYNC_CREATE_EVENT` still set
- **THEN** a **new** event is minted (a fresh `eventId`), reflecting the non-idempotent contract — the
  previous event is not reused, and under `autoJoin` any current membership is left before joining the
  new one

#### Scenario: Attestation is made fresh before the create request
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` on a device whose attestation token is
  stale or absent
- **THEN** the app obtains a fresh attestation token before issuing `POST /events`, so the create is
  not silently lost to an attestation rejection

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_CREATE_EVENT` in its
  environment
- **THEN** no event is minted and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build

#### Scenario: Invalid payload is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` set to a value that is not valid
  `base64url(JSON)`, is missing `name`, or carries an unknown key
- **THEN** the strict codec rejects it and no event is minted and no membership side effect occurs

### Requirement: Developer launch-environment LEAVE trigger

The iOS app SHALL read a `SNAPSYNC_LEAVE` variable from the process environment **once per process
launch**. Its **presence** (any value) SHALL trigger leaving the current membership through the existing
leave use-case (capability `leave-event`): cancel in-flight downloads, stop the upload producer, clear
the persisted config, and notify the backend best-effort. When the device is **not** currently joined,
the trigger SHALL be a **no-op**. A failed backend notification SHALL NOT block clearing the local
config (leaving is best-effort by the leave use-case's own contract).

The trigger SHALL be applied **at most once per process** and SHALL rely on the developer-launch-only
injectability of a process-environment variable, so it is inert in production **with no compile-time
guard**.

#### Scenario: Present variable leaves the current event
- **WHEN** the app is cold-launched with `SNAPSYNC_LEAVE` present while joined to an event
- **THEN** the app leaves that membership (downloads cancelled, producer stopped, config cleared,
  backend notified best-effort) and returns to the unjoined resting state

#### Scenario: Present variable while unjoined is a no-op
- **WHEN** the app is cold-launched with `SNAPSYNC_LEAVE` present while **not** joined to any event
- **THEN** no side effect occurs

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_LEAVE` in its
  environment
- **THEN** no leave side effect occurs and behavior is identical to the app without this feature, with
  no compile-time flag distinguishing the build

### Requirement: Ordered application of membership-mutating launch triggers

The app SHALL apply the membership-mutating launch triggers (`SNAPSYNC_LEAVE`, `SNAPSYNC_CREATE_EVENT`,
`SNAPSYNC_EVENT_LINK`) in the **fixed order** `leave → create → event-link`, **sequentially** — each
awaited to completion before the next — so combinations set in the same launch are well-defined and each
later step observes the state the earlier ones produced (e.g. a `SNAPSYNC_LEAVE` clears the config
**before** a `SNAPSYNC_CREATE_EVENT` mints and the create/join steps read the post-leave membership).
Each trigger remains independent: a launch MAY set any subset, and an absent trigger contributes nothing
to the order.

A **forge** launch (`SNAPSYNC_FORGE_STATE` naming a recognized state) SHALL ignore **all** three
membership-mutating triggers — it mints nothing, leaves nothing, and provisions nothing. This inertness
SHALL be **structural** (the shell's single mode switch selects a forge delegate holding no reference to
the live stack), consistent with the forge-state trigger's existing precedence over `SNAPSYNC_EVENT_LINK`.

#### Scenario: Leave and create apply in order
- **WHEN** the app is cold-launched with both `SNAPSYNC_LEAVE` present and `SNAPSYNC_CREATE_EVENT` set,
  while joined to an event
- **THEN** the app first leaves the current membership, then mints the new event — the create step
  observes the cleared config (so an `autoJoin` create joins fresh rather than treating it as a switch)

#### Scenario: Forge wins over create and leave
- **WHEN** the app is cold-launched with a recognized `SNAPSYNC_FORGE_STATE` and any of
  `SNAPSYNC_CREATE_EVENT` / `SNAPSYNC_LEAVE` in its environment
- **THEN** the forged frame renders, the membership triggers are ignored, nothing is minted, nothing is
  left, and nothing is provisioned — the forge delegate has no route to the live stack
