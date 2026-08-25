## MODIFIED Requirements

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
