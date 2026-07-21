# push-registration Delta

## RENAMED Requirements

- FROM: `### Requirement: Registration timing — launch and rotation`
- TO: `### Requirement: Registration timing — launch, join, and rotation`

## MODIFIED Requirements

### Requirement: Registration timing — launch, join, and rotation

The app SHALL register the token when it becomes available after launch, **on join** (when the device
provisions an event), and again whenever the APNs token rotates (a new token delivered by the OS).
Registration SHALL be idempotent — re-registering the same token overwrites an identical
`devices/<deviceId>.json` (last-write-wins at the endpoint) — so repeated launches and joins with an
unchanged token are harmless.

Registering on join exists to close a **warm-rejoin** window opened by the scheduled cleanup (capability
`scheduled-cleanup`): when the sweep collects a fully-orphaned device's `devices/<deviceId>.json`, a
device that then rejoins **without** a cold launch would otherwise have no registered token on the
backend until its next launch, and silent pushes to it would be skipped. Registering on join restores the
token immediately. Registration SHALL NOT be tied to every foreground (too frequent); launch, join, and
rotation are the triggers.

#### Scenario: Registration fires on launch once the token is available

- **WHEN** the app launches and the OS delivers the APNs token
- **THEN** `PushRegistration` runs for that token

#### Scenario: Registration fires on join

- **WHEN** the device provisions (joins) an event and an APNs token is available
- **THEN** `PushRegistration` runs for that token, re-registering it with the backend

#### Scenario: Rotation re-registers

- **WHEN** the OS delivers a new (rotated) APNs token
- **THEN** `PushRegistration` runs again with the new token, replacing the stored config
