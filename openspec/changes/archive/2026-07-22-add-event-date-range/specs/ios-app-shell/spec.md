# ios-app-shell Specification

## MODIFIED Requirements

### Requirement: Developer launch-environment CREATE trigger

The iOS app SHALL read a `SNAPSYNC_CREATE_EVENT` variable from the process environment **once per
process launch**. When present, its value SHALL be a `base64url(JSON)` payload decoded by a dedicated,
**strict** `model/` codec (rejecting unknown keys, tested in `commonTest` so it runs on both JVM and
`iosSimulatorArm64`) carrying a **required** `name` and the optional keys `startsAt` (a canonical
`…Z` UTC string; default **now**), `endsAt` (a canonical `…Z` UTC string; when absent, the create falls
back exactly as today — the backend stamps the legacy `startsAt + 30d`, capability `event-creation`),
`autoJoin` (default `false`), `minPhotoDate`, `direction`, and `saveToAlbum`. A payload that is absent,
not valid `base64url(JSON)`, missing `name`, or carrying an unknown key SHALL produce **no** side effect.

When the payload is valid the app SHALL mint the event through the **existing attest-gated
`POST /events`** path (the same event-creation client the interactive create uses; it SHALL introduce no
second create path), passing `endsAt` through to that request the same way `startsAt` is passed (an absent
`endsAt` sends none, so the backend applies its fallback), and SHALL ensure an attestation token is fresh
**before** that request so a cold-launch create is not lost to a not-yet-ready token. Then:

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

#### Scenario: A supplied endsAt is passed through to the mint
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying a `name` and an `endsAt`
  (a canonical `…Z` string)
- **THEN** the `POST /events` request carries that `endsAt`, so the minted event's window ends at the
  supplied instant rather than the backend fallback

#### Scenario: An absent endsAt falls back as today
- **WHEN** the app is cold-launched with `SNAPSYNC_CREATE_EVENT` carrying a `name` and **no** `endsAt`
- **THEN** the `POST /events` request sends no `endsAt` and the backend stamps its legacy fallback —
  behavior identical to before this change

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
