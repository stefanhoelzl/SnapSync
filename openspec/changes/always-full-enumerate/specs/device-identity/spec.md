## MODIFIED Requirements

### Requirement: The device id is readable by background work on a locked device

The device id's Keychain item SHALL be stored with an accessibility class that permits reads while the
device is **locked**, once the device has been unlocked at least once since boot
(`kSecAttrAccessibleAfterFirstUnlock`). Background work — a `BGProcessingTask`, a silent push, a
background `URLSession` completion, and the OS-scheduled upload extension — runs while the device is
idle and therefore usually locked, and each such context resolves the device id.

The item SHALL NOT be restricted to the device (`…ThisDeviceOnly`): it SHALL remain restorable from an
encrypted backup, so that a restored device recovers the **same** id as the app container (the ledger)
that is restored alongside it. Decision record:
`changes/archive/2026-07-14-fix-locked-device-keychain-access`.

#### Scenario: A locked background wake resolves the device id

- **WHEN** background work resolves the device id while the device is locked, and the device has been
  unlocked at least once since boot
- **THEN** the persisted id is returned, nothing is minted, and no error is raised

#### Scenario: The id is restorable alongside the app container

- **WHEN** an encrypted backup is restored to a device
- **THEN** the restored device reads the same device id it had before, consistent with the ledger
  restored with it, and therefore does not re-upload its already-stored resources
