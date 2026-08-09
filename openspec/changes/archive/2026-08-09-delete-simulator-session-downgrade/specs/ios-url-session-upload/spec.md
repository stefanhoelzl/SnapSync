## MODIFIED Requirements

### Requirement: The tier-force flag alters neither transport nor tier exclusivity

The development tier-force flag (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`) SHALL select the **tier** and
nothing else. It SHALL NOT change the transport: uploads SHALL run over a background `URLSession`
whether or not the flag is set.

The app-driven tier SHALL use **one** transport on every host. There SHALL be no host determination
anywhere in the composition or the adapters: the process SHALL NOT read `SIMULATOR_DEVICE_NAME` (or any
equivalent), and no simulator-specific session configuration SHALL exist. A background `URLSession`
demonstrably runs on the iOS simulator — `getAllTasksWithCompletionHandler` answers and an upload task
executes through to `didCompleteWithError` (measured 2026-08-09, `iosSimulatorArm64`, macOS 26.5.2 /
Xcode 26.6; decision record: `changes/archive/…-delete-simulator-session-downgrade`) — so the downgrade
this requirement previously provided for defended nothing. **Whether the OS relaunches a terminated app
to deliver `handleEventsForBackgroundURLSession` on a simulator is NOT evidenced by that measurement and
remains unproven.**

Forcing the app-driven tier on a device whose OS supports the OS-driven tier SHALL NOT register the
PhotoKit upload extension (`upload-lifecycle`, "Exactly one producer per process"), so the two tiers
are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

This makes the flag a faithful device-testing lever: it is the only way to exercise the app-driven
tier on a hardware device whose OS is ≥26.1.

#### Scenario: Forcing the tier keeps the background transport

- **WHEN** the app-driven tier is forced on a physical device
- **THEN** uploads run over a background `URLSession`, exactly as they do on a device whose OS selects the tier by version

#### Scenario: The transport does not vary by host

- **WHEN** the app-driven tier runs on an iOS simulator
- **THEN** it creates the same background `URLSession` it creates on a physical device, and no code path
  selects a foreground session for any host

#### Scenario: Forcing the tier does not enable the extension

- **WHEN** the app-driven tier is forced on a physical device whose OS is ≥26.1
- **THEN** `setUploadJobExtensionEnabled` is never called, only the app-driven producer is live, and exactly one process holds the `LedgerWriter`
