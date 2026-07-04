## ADDED Requirements

### Requirement: Remote-notification capability declaration

The iOS app SHALL declare the push capability required to receive silent remote notifications: the
`aps-environment` entitlement in `iosApp.entitlements` (`development` for dev/sideloaded builds,
`production` for TestFlight/App Store, driven by the build configuration) and the `remote-notification`
value in `UIBackgroundModes` in `Info.plist` (so a `content-available` push can wake the app in the
background). The APNs environment the device registers (`sandbox` | `production`) SHALL be a
compile-time value baked from the build configuration (`Config.xcconfig`), consistent with the
`aps-environment` the entitlement declares.

#### Scenario: The app declares the push entitlement and background mode

- **WHEN** the app is built
- **THEN** `iosApp.entitlements` carries `aps-environment` and `Info.plist` `UIBackgroundModes`
  includes `remote-notification`

#### Scenario: Dev builds register the sandbox environment

- **WHEN** a dev/sideloaded build registers its token
- **THEN** the entitlement is `development` and the reported APNs `env` is `sandbox`; a
  TestFlight/App Store build reports `production`

### Requirement: Register for remote notifications and forward the token

On launch the app SHALL register for remote notifications (`UIApplication.registerForRemoteNotifications`)
and, when the OS delivers the APNs device token (`didRegisterForRemoteNotificationsWithDeviceToken`),
forward the token — as the encoded token string plus the compile-time `env` — into the Kotlin push
seam (`:capability:push`) for registration with the backend. A registration failure
(`didFailToRegisterForRemoteNotificationsWithError`) SHALL be logged and SHALL NOT crash or block the
app. The Swift `AppDelegate` SHALL perform **no** decision logic — it is a pass-through to Kotlin,
consistent with the existing deeplink / background-URL-session hooks.

#### Scenario: The delivered device token reaches the push seam

- **WHEN** the OS delivers the APNs device token to the `AppDelegate`
- **THEN** the token string and the compile-time `env` are forwarded to the Kotlin push registration
  path, which writes the device config to the backend

#### Scenario: A registration error does not crash the app

- **WHEN** remote-notification registration fails
- **THEN** the failure is logged and the app continues running normally

### Requirement: Forward an incoming silent push to the receiver seam

The `AppDelegate` SHALL forward an incoming remote notification to the Kotlin `PushReceiver` seam and
then call the OS fetch completion handler, performing no parsing or decision logic in Swift (it is a
pass-through, like the existing deeplink and background-URL-session hooks). The OS entry point is the
app-delegate remote-notification callback that supplies the payload and a completion handler. In this
infrastructure phase the wired receiver logs receipt (capability `push-registration`), so an incoming
silent push is observable without any use-case behavior.

#### Scenario: An incoming push is routed to Kotlin

- **WHEN** the app receives a silent remote notification
- **THEN** the `AppDelegate` forwards it to the Kotlin `PushReceiver` and calls the OS completion
  handler, with no parsing or decision in Swift
