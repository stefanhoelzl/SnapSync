## MODIFIED Requirements

### Requirement: Enable the background-upload extension on grant

When photo-library access is (or becomes) full (`.readWrite` → `GRANTED`), the app SHALL enable the
background-upload extension (`PHPhotoLibrary.setUploadJobExtensionEnabled(true)`) so the system can
invoke it — but only **after** the join reconciliation gate has been satisfied for the configured
event (see `event-rejoin-reconciliation`). When the gate triggers a join (an event is configured and
the ledger is empty and no join is settled this process), the app SHALL run the join **with the
extension disabled** — fetch the event file list, enumerate the library, atomically seed
already-stored photos as `COMPLETED` (`resetTo`), and clear the discovery cursor — and SHALL enable
the extension only once the join succeeds. When the gate does not trigger (the ledger already holds
rows), the app SHALL enable the extension directly without fetching, enumerating, or seeding. The app
SHALL create no upload jobs and perform no uploads; the one-time library enumeration for the join seed
is the app's only producer-adjacent work, while per-upload discovery and job creation remain the
extension's. The enable call SHALL be idempotent-safe to repeat on each grant/foreground.

#### Scenario: Granting full access runs the gate, then enables
- **WHEN** photo-library permission transitions to `GRANTED` with a configured event and an empty ledger
- **THEN** the app runs the join (seed + cursor clear) with the extension disabled and calls
  `setUploadJobExtensionEnabled(true)` only after the join succeeds

#### Scenario: Non-empty ledger enables without re-joining
- **WHEN** permission is `GRANTED` (or the app foregrounds) and the ledger already holds rows
- **THEN** the app enables the extension without fetching, enumerating, or seeding

#### Scenario: The app never uploads
- **WHEN** the app is running with access granted
- **THEN** it creates no upload jobs and runs no uploads; per-upload discovery and job creation happen
  in the extension process

### Requirement: Developer launch-environment config trigger

The iOS app SHALL read a `SNAPSYNC_DEEPLINK` variable from the process environment **once per
process launch** and, when it is present and holds a valid `snapsync://config?…` URL, provision the
event **identically to a scanned deeplink** — forwarding the raw URL string to
`SnapSyncRoot.onOpenUrl(_:)`, which performs the authoritative decode/validate and, on success,
provisions the event: a **different** eventId resets the ledger and runs the join reconciliation; the
**same** eventId against a non-empty ledger is a no-op (see `event-rejoin-reconciliation`).
Provisioning SHALL NOT force a fresh whole-library upload — re-provision reconciles against storage
(seeding already-stored photos) rather than re-uploading. The read SHALL reuse the existing
`deeplink-config` decoder and the `onOpenUrl` path verbatim; it SHALL NOT introduce a second decoder
or config-construction path, and SHALL perform no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose view or
view-controller recreation within the same process. A subsequent **cold launch** with the variable
still set SHALL provision again (which reconciles; it does not force a re-upload).

When the variable is **absent**, the app SHALL behave exactly as without this feature (no
provisioning side effect). The trigger SHALL rely on the fact that a process-environment variable is
only injectable via a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`); launches
from SpringBoard or TestFlight carry no such variable, so the trigger is inert in production **with
no compile-time guard**. When the variable is present but holds an invalid or non-`snapsync://`
value, the app SHALL produce no provisioning side effect (the existing decoder rejects it).

#### Scenario: Cold launch with the variable provisions once
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a valid `snapsync://config?v=3&d=…`
  URL for an event not currently configured
- **THEN** the app provisions that event exactly as a scanned QR would — resetting the ledger and
  running the join reconciliation — and forcing a view/view-controller recreation within that same
  process does not re-apply the trigger or re-reset the ledger

#### Scenario: A subsequent cold launch re-provisions and reconciles
- **WHEN** the app is launched again in a fresh process with `SNAPSYNC_DEEPLINK` still set
- **THEN** the app re-provisions and reconciles against storage; it does **not** force a fresh
  whole-library re-upload

#### Scenario: Re-provision does not re-upload already-stored photos
- **WHEN** the variable provisions an event whose photos are already stored, against an empty ledger
- **THEN** those photos are seeded `COMPLETED` by the join and are not re-uploaded

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight, with no `SNAPSYNC_DEEPLINK` in its
  environment
- **THEN** no provisioning side effect occurs and behavior is identical to the app without this
  feature, with no compile-time flag distinguishing the build

#### Scenario: Invalid environment value is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a malformed or non-`snapsync://`
  value
- **THEN** the existing decoder rejects it and no provisioning side effect occurs
