# push-registration Specification

## Purpose

The device end of the backend→app channel: the app acquires its APNs token, registers it with the backend
(`api-endpoints`), re-registers on rotation, and exposes a `PushReceiver` seam that an incoming
silent push is forwarded into.

The seams exist so that logic stays testable while `:app:ios` stays wiring-only — token acquisition and push
delivery are OS callbacks, so both are ports, and everything downstream of them is ordinary tested code.

Silent push is a **best-effort accelerant**, never a delivery guarantee: it is layered over the existing
foreground discovery and the `BGProcessingTask` backstop, both of which remain correct if no push ever
arrives.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.
## Requirements
### Requirement: APNs token acquisition seam

The system SHALL define a `PushTokenSource` (`:domain` `ports/`, `commonMain`) that yields the device's
current APNs push token together with its APNs environment (`sandbox` | `production`), and notifies on
rotation (a `StateFlow` of the latest token). Because the token is **OS-push-delivered, not pulled**,
the source is a settable holder — it exposes a `deliver(hexToken)` method that both the app-shell wiring
and tests call, so it is its own test fake (no separate implementation is needed) and the registration
logic is exercised on both the JVM and `iosSimulatorArm64`. The environment SHALL be an **injected
compile-time** value (sourced from the build's `aps-environment`, e.g. the `apnsEnv` value baked into the
bundled `Deployment.plist`), not detected at runtime — mirroring how the upload host base is injected. The real APNs
acquisition (calling `registerForRemoteNotifications` and receiving the token) is **app-shell wiring**
(the Swift `AppDelegate` → `SnapSyncRoot.onPushToken(hex)` → `deliver`) in `:app:ios`, not a
core type.

#### Scenario: The seam yields a token and its environment

- **WHEN** the OS has delivered an APNs device token
- **THEN** `PushTokenSource` exposes that token string and its injected `env`

#### Scenario: A test fake drives the seam

- **WHEN** a test sets the fake `PushTokenSource` to a token/env
- **THEN** the registration logic runs against that value with no platform or network call

### Requirement: Token registration writes the device config

The system SHALL provide a `PushRegistration` use-case (`:domain` `feature/push`, `commonMain`,
tested — re-homed from the deleted `:capability:push` at the migration finale) that, given the
`deviceId` (from the `device-identity` seam), the backend host (injected compile-time base), and a
`pushToken` (`token` + `env` from `PushTokenSource`), performs a `PUT <host>/devices/<deviceId>`
with the JSON body `{ "pushToken": { "kind": "apns", "token": <token>, "env": <env> } }` via an
**injected HTTP client seam** (faked in tests; the real client is the shared Darwin/Ktor client at the
composition root). It SHALL build the request with string-building only — no crypto, no signing — and
SHALL NOT read or require any event id (registration is event-independent). A non-2xx or failed write
SHALL be handled without throwing to the caller (registration is retried on the next trigger; a failed
registration SHALL NOT block join, upload, or download).

#### Scenario: Registration PUTs the config document

- **WHEN** `PushRegistration` runs with `deviceId`, host, and an `apns` token/env
- **THEN** it issues `PUT <host>/devices/<deviceId>` with body `{ "pushToken": { "kind":
  "apns", "token": <token>, "env": <env> } }`

#### Scenario: A failed registration does not disrupt the app

- **WHEN** the config `PUT` returns a non-2xx status or the client errors
- **THEN** the failure is absorbed (no exception to the caller) and the app's join/upload/download are
  unaffected

#### Scenario: Registration carries no event id

- **WHEN** `PushRegistration` builds its request
- **THEN** the URL and body contain no `eventId` (the token is device-scoped, event-independent)

### Requirement: Silent-push receive seam

`:domain`'s `ports/` SHALL define the `PushReceiver` seam invoked when the app receives a silent
(`content-available`) push, carrying the pushed **`eventId`** (delivered as the push payload's
top-level `eventId` key, capability `apns-push-sender`). The seam SHALL be **asynchronous**
(suspending / completion-shaped) so the app-shell can **await** the receiver's synchronous work (the
union read + download enqueue) **before** signalling the OS background-fetch completion handler —
keeping the app alive through the push's execution window rather than risking suspension before any
transfer is enqueued. The wired implementation SHALL trigger download discovery
`reconcile(eventId)` (capability `photo-download`) **guarded** on the pushed `eventId` equalling the
device's **active event** (from the config seam): a push whose `eventId` is not the active event —
**including when no event is configured** — SHALL be a **no-op** (no reconcile). The guard exists
because leave is local-only (capability `leave-event`): a left event's backend membership persists and
keeps pushing this device, so an unguarded reconcile would silently re-pull a left event's new photos.
The guard + reconcile logic SHALL live in a **tested** feature (`DownloadPushReceiver`, `:domain`
`feature/download`, exercised on JVM and `iosSimulatorArm64`), never parked in the untested app shell.
The cross-arm **fan-out** — one push is news to both arms — SHALL be the `flow/SilentPush` trigger
(`:domain` `flow/`, built in `compose/`; it absorbed the former `FanOutPushReceiver`): the receivers run
in order (download, then the upload arm's on the app-driven tier), isolated so one failure never robs
the other of the scarce wake. The flow SHALL take the OS payload **whole** (migration step 12, the
transcriber law): the app-shell wiring forwards the raw `userInfo` dictionary and the completion
handler; the `eventId` extraction is the tested `model/` payload codec (`pushEventId`), applied
inside the flow — a payload with no usable `eventId` fans out to no arm, and the completion handler
is released either way. Before fanning out, the flow SHALL re-read the persisted membership into
the config StateFlow (the trigger-time `reloadConfig` re-read — see `ios-app-shell`), so the
receivers' active-event guards read current state.

#### Scenario: A push for the active event reconciles

- **WHEN** a silent push carrying `eventId` = the device's active event is delivered and routed to
  `PushReceiver`
- **THEN** the receiver runs `reconcile(eventId)` (discover foreign assets, enqueue downloads, import
  staged assets)

#### Scenario: A push for a non-active or left event is a no-op

- **WHEN** the pushed `eventId` differs from the active event, or no event is configured
- **THEN** the receiver performs no reconcile (a left event's persisting membership cannot re-pull
  photos)

#### Scenario: A payload without an eventId wakes no arm

- **WHEN** a silent push arrives whose `userInfo` carries no usable top-level `eventId` string
- **THEN** the flow fans out to no receiver, logs the miss, and the OS completion handler is still
  released

#### Scenario: The receive path awaits before the OS handler

- **WHEN** a silent push for the active event is handled
- **THEN** the app-shell signals the OS background-fetch completion handler only after the flow's
  synchronous portion (payload decode, membership re-read, attestation wake) has completed — the
  fan-out's reconcile escapes on the app scope, and the enqueued background transfers then continue
  on their own

#### Scenario: The receiver is replaceable

- **WHEN** a future change supplies a different `PushReceiver` implementation
- **THEN** it is invoked on receipt with the pushed `eventId` and no change to the app-shell receive
  wiring

### Requirement: Registration timing — launch, join, and rotation

The app SHALL register the token when it becomes available after launch, **on join** (when the device
provisions an event), and again whenever the APNs token rotates (a new token delivered by the OS).
Registration SHALL be idempotent — re-registering the same token overwrites the device's stored
registration (last-write-wins at the endpoint) — so repeated launches and joins with an unchanged token
are harmless.

The registration write SHALL be treated as **refusable**. It requires an attestation record on the
backend, and answers `401` when there is none (capability `api-endpoints`); the app already recovers from
any `401` by obtaining a fresh credential, and obtaining one SHALL re-send the registration. Without that
retry the device would go unregistered until its next launch, because it writes its registration only once
per token the OS delivers.

Registering on join exists to close a **warm-rejoin** window: a device can hold a backend record whose
push registration is absent, and would then receive no silent pushes until its next launch. Two things
produce that state — a device that re-attested after its record was collected (attestation records no push
token, so the recreated row's registration columns are empty), and one whose registration write was
refused. Registering on join restores it immediately.

The window is **narrower than it was**, and the reason is worth keeping: the scheduled cleanup no longer
collects a device's record while a token minted for it can still verify (capability
`scheduled-cleanup`). A device whose record is gone therefore cannot rejoin warm — it holds no usable
credential, so it must attest first, and attesting recreates the record. What remains is the absent
*registration*, not an absent record.

Registration SHALL NOT be tied to every foreground (too frequent); launch, join, and rotation are the
triggers.

#### Scenario: Registration fires on launch once the token is available

- **WHEN** the app launches and the OS delivers the APNs token
- **THEN** `PushRegistration` runs for that token

#### Scenario: Registration fires on join

- **WHEN** the device provisions (joins) an event and an APNs token is available
- **THEN** `PushRegistration` runs for that token, re-registering it with the backend

#### Scenario: Rotation re-registers

- **WHEN** the OS delivers a new (rotated) APNs token
- **THEN** `PushRegistration` runs again with the new token, replacing the stored registration

#### Scenario: A refused registration is re-sent once a fresh credential is obtained

- **WHEN** the registration write is refused because the backend holds no attestation for the device
- **THEN** the app attests afresh and re-sends the registration, without waiting for the OS to deliver
  another APNs token

