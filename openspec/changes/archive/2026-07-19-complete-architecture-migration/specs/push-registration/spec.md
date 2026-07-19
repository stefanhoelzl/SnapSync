# push-registration — delta for complete-architecture-migration

## MODIFIED Requirements

### Requirement: APNs token acquisition seam

The system SHALL define a `PushTokenSource` (`:domain` `ports/`, `commonMain`) that yields the device's
current APNs push token together with its APNs environment (`sandbox` | `production`), and notifies on
rotation (a `StateFlow` of the latest token). Because the token is **OS-push-delivered, not pulled**,
the source is a settable holder — it exposes a `deliver(hexToken)` method that both the app-shell wiring
and tests call, so it is its own test fake (no separate implementation is needed) and the registration
logic is exercised on both the JVM and `iosSimulatorArm64`. The environment SHALL be an **injected
compile-time** value (sourced from the build's `aps-environment`, e.g. a `Config.xcconfig`-baked
`APNS_ENV`), not detected at runtime — mirroring how the upload host base is injected. The real APNs
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
