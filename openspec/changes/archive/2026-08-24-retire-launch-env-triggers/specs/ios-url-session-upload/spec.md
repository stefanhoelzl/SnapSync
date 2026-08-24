## REMOVED Requirements

### Requirement: The tier-force flag alters neither transport nor tier exclusivity

**Reason**: The development tier-force flag (`SNAPSYNC_FORCE_URLSESSION_UPLOAD`) is deleted with the rest of
the launch-environment trigger surface. Its transport and exclusivity clauses are not lost — they are
independent of the flag and are restated below as "The app-driven tier uses one transport on every host".

**Migration**: Selecting the app-driven tier on a device whose OS supports the OS-driven one has **no
replacement in this change**. Restoring it belongs to producer resolution, which replaces
`ComposedProducers` and `selectedProducer()` with one resolved producer from a pure
`resolve(osFacts, permission, forced)` — where `forced` is a runtime-readable input rather than a
launch-time one. That input must survive an **OS-initiated cold relaunch**: a process the OS relaunches to
deliver `handleEventsForBackgroundURLSession` resolves its tier before any request can arrive, so an
in-memory value cannot serve.

Until then, the app-driven tier remains reachable under a **`LIMITED`** photo grant, where the OS never
invokes the extension (measured: zero `process()` invocations over 22 minutes; capability
`ios-photokit-upload`) and the arm selects the app-driven producer. What is not reachable in that window is
the app-driven tier under a **full** grant — the full-library discovery walk, since a partial grant feeds
discovery the in-memory selection snapshot instead.

## ADDED Requirements

### Requirement: The app-driven tier uses one transport on every host

The app-driven tier SHALL use **one** transport on every host: uploads SHALL run over a background
`URLSession` regardless of the host the process runs on.

There SHALL be no host determination anywhere in the composition or the adapters: the process SHALL NOT read
`SIMULATOR_DEVICE_NAME` (or any equivalent), and no simulator-specific session configuration SHALL exist. A
background `URLSession` demonstrably runs on the iOS simulator — `getAllTasksWithCompletionHandler` answers
and an upload task executes through to `didCompleteWithError` (measured 2026-08-09, `iosSimulatorArm64`,
macOS 26.5.2 / Xcode 26.6; decision record: `changes/archive/2026-08-09-delete-simulator-session-downgrade`)
— so the downgrade this requirement's predecessor provided for defended nothing. **Whether the OS relaunches
a terminated app to deliver `handleEventsForBackgroundURLSession` on a simulator is NOT evidenced by that
measurement and remains unproven.**

Wherever the app-driven tier is selected on a device whose OS supports the OS-driven tier, the PhotoKit
upload extension SHALL NOT be registered (`upload-lifecycle`, "Exactly one producer per process"), so the two
tiers are never simultaneously live and the `sync-ledger` single-record-writer invariant holds.

#### Scenario: The transport does not vary by host

- **WHEN** the app-driven tier runs on an iOS simulator
- **THEN** it creates the same background `URLSession` it creates on a physical device, and no code path
  selects a foreground session for any host

#### Scenario: The app-driven tier does not enable the extension

- **WHEN** the app-driven tier is live on a device whose OS is ≥26.1 — because the photo grant is partial, or
  because a later runtime selection chose it
- **THEN** `setUploadJobExtensionEnabled(true)` is not called for that producer, only the app-driven producer
  is live, and exactly one process holds the `LedgerWriter`
