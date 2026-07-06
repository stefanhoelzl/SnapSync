## ADDED Requirements

### Requirement: Config removed when the device is fully orphaned

The config object `devices/<deviceId>.json` SHALL be deleted as part of the leave cascade's garbage
collection (see `event-leave-endpoint`) when, and only when, the device becomes **fully orphaned** — it
appears in no surviving event as either an active `<deviceId>.json` or a departed `<deviceId>.left.json`
manifest. There SHALL be no dedicated config-delete HTTP route: the config is removed by the same cascade
that deletes the device's `files/devices/<deviceId>/` byte partition, so a device's config outlives its
byte partition only transiently (both go together). A device that later reinstalls or rejoins re-registers
its config via the existing `PUT` (the device id is Keychain-stable), so config deletion is not
destructive to a returning device.

#### Scenario: Orphaned device's config is collected with its bytes

- **WHEN** the leave cascade determines a device appears in no surviving event
- **THEN** it deletes `devices/<deviceId>.json` together with every object under `files/devices/<deviceId>/`

#### Scenario: Config retained while the device is still in an event

- **WHEN** an event is reaped but the device still has a manifest in another surviving event
- **THEN** `devices/<deviceId>.json` is retained (the device is not orphaned)
