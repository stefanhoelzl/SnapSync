# push-registration Specification

## Purpose

The device end of the backend→app channel: the app acquires its APNs token, registers it with the backend
(`device-config-endpoint`), re-registers on rotation, and exposes a `PushReceiver` seam that an incoming
silent push is forwarded into.

The seams exist so that logic stays testable while `:app:ios` stays wiring-only — token acquisition and push
delivery are OS callbacks, so both are ports, and everything downstream of them is ordinary tested code.

Silent push is a **best-effort accelerant**, never a delivery guarantee: it is layered over the existing
foreground discovery and the `BGProcessingTask` backstop, both of which remain correct if no push ever
arrives.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.

## Requirements
### Requirement: APNs token acquisition seam

The `:capability:push` module SHALL define a `PushTokenSource` in `commonMain` that yields the device's
current APNs push token together with its APNs environment (`sandbox` | `production`), and notifies on
rotation (a `StateFlow` of the latest token). Because the token is **OS-push-delivered, not pulled**,
the source is a settable holder — it exposes a `deliver(hexToken)` method that both the app-shell wiring
and tests call, so it is its own test fake (no separate implementation is needed) and the registration
logic is exercised on both the JVM and `iosSimulatorArm64`. The environment SHALL be an **injected
compile-time** value (sourced from the build's `aps-environment`, e.g. a `Config.xcconfig`-baked
`APNS_ENV`), not detected at runtime — mirroring how the upload host base is injected. The real APNs
acquisition (calling `registerForRemoteNotifications` and receiving the token) is **app-shell wiring**
(the Swift `AppDelegate` → `SnapSyncRoot.onPushToken(hex)` → `deliver`) in `:app:ios`, not a
`:capability:push` type.

#### Scenario: The seam yields a token and its environment

- **WHEN** the OS has delivered an APNs device token
- **THEN** `PushTokenSource` exposes that token string and its injected `env`

#### Scenario: A test fake drives the seam

- **WHEN** a test sets the fake `PushTokenSource` to a token/env
- **THEN** the registration logic runs against that value with no platform or network call

### Requirement: Token registration writes the device config

The module SHALL provide a `PushRegistration` use-case (in `commonMain`, tested) that, given the
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

### Requirement: Registration timing — launch and rotation

The app SHALL register the token when it becomes available after launch, and again whenever the APNs
token rotates (a new token delivered by the OS). Registration SHALL be idempotent — re-registering the
same token overwrites an identical `devices/<deviceId>.json` (last-write-wins at the endpoint) — so repeated
launches with an unchanged token are harmless.

#### Scenario: Registration fires on launch once the token is available

- **WHEN** the app launches and the OS delivers the APNs token
- **THEN** `PushRegistration` runs for that token

#### Scenario: Rotation re-registers

- **WHEN** the OS delivers a new (rotated) APNs token
- **THEN** `PushRegistration` runs again with the new token, replacing the stored config

### Requirement: Silent-push receive seam

The module SHALL define a `PushReceiver` seam invoked when the app receives a silent
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
The guard + reconcile logic SHALL live in a **tested** module (exercised on JVM and
`iosSimulatorArm64`), never parked in the untested app shell; the app-shell wiring only forwards the
OS callback (the raw `eventId` and the completion handler) into the seam.

#### Scenario: A push for the active event reconciles

- **WHEN** a silent push carrying `eventId` = the device's active event is delivered and routed to
  `PushReceiver`
- **THEN** the receiver runs `reconcile(eventId)` (discover foreign assets, enqueue downloads, import
  staged assets)

#### Scenario: A push for a non-active or left event is a no-op

- **WHEN** the pushed `eventId` differs from the active event, or no event is configured
- **THEN** the receiver performs no reconcile (a left event's persisting membership cannot re-pull
  photos)

#### Scenario: The receive path awaits before the OS handler

- **WHEN** a silent push for the active event is handled
- **THEN** the app-shell signals the OS background-fetch completion handler only after the receiver's
  synchronous portion (union read + enqueue) has completed, so the enqueue is not cut short by
  suspension

#### Scenario: The receiver is replaceable

- **WHEN** a future change supplies a different `PushReceiver` implementation
- **THEN** it is invoked on receipt with the pushed `eventId` and no change to the app-shell receive
  wiring

