## MODIFIED Requirements

### Requirement: Developer launch-environment config trigger

The iOS app SHALL read a `SNAPSYNC_DEEPLINK` variable from the process environment **once per
process launch** and, when it is present and holds a valid `snapsync://config?…` URL, forward the raw
URL string to `SnapSyncRoot.onOpenUrl(_:)`, which performs the authoritative decode/validate and drives
the join gate (capability `join-event`). Because a scanned/opened deeplink now shows a confirmation
gate rather than provisioning silently, the developer trigger's URL SHALL carry **`autoJoin = true`**
so the gate **auto-confirms** headlessly (the headless launch path cannot tap a confirm control): the
app fetches the event details and, on success, enrolls and provisions with no user interaction — a
**different** eventId leaves any current event first and runs the join reconciliation; the **same**
eventId is a no-op that neither re-enrolls nor re-resets (see `event-rejoin-reconciliation` and
`join-event`). Provisioning SHALL NOT force a fresh whole-library upload — re-provision reconciles
against storage (seeding already-stored photos) rather than re-uploading. A `SNAPSYNC_DEEPLINK` URL
**without** `autoJoin` SHALL open the interactive join gate (which then awaits a tap). The read SHALL
reuse the existing `deeplink-config` decoder and the `onOpenUrl` path verbatim; it SHALL NOT introduce
a second decoder or config-construction path, and SHALL perform no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose view or
view-controller recreation within the same process. A subsequent **cold launch** with the variable
still set SHALL run again (which reconciles; it does not force a re-upload).

When the variable is **absent**, the app SHALL behave exactly as without this feature (no
provisioning side effect). The trigger SHALL rely on the fact that a process-environment variable is
only injectable via a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`); launches
from SpringBoard or TestFlight carry no such variable, so the trigger is inert in production **with
no compile-time guard**. When the variable is present but holds an invalid or non-`snapsync://`
value, the app SHALL produce no provisioning side effect (the existing decoder rejects it).

#### Scenario: Cold launch with an autoJoin variable provisions once
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a valid `snapsync://config?v=3&d=…`
  URL carrying `autoJoin = true` for an event not currently configured
- **THEN** the gate auto-confirms — the app fetches details, enrolls, and provisions that event exactly
  as a confirmed scan would, and forcing a view/view-controller recreation within that same process
  does not re-apply the trigger

#### Scenario: A subsequent cold launch re-runs and reconciles
- **WHEN** the app is launched again in a fresh process with `SNAPSYNC_DEEPLINK` still set
- **THEN** the app re-runs the gate and reconciles against storage; it does **not** force a fresh
  whole-library re-upload

#### Scenario: Re-provision does not re-upload or re-enroll the already-joined event
- **WHEN** the variable provisions the event the device is already joined to, against an empty ledger
- **THEN** already-stored photos are seeded `COMPLETED` by the join, nothing is re-uploaded, and no
  empty-manifest enrollment is re-issued (per `join-event`)

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight, with no `SNAPSYNC_DEEPLINK` in its
  environment
- **THEN** no provisioning side effect occurs and behavior is identical to the app without this
  feature, with no compile-time flag distinguishing the build

#### Scenario: Invalid environment value is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a malformed or non-`snapsync://`
  value
- **THEN** the existing decoder rejects it and no provisioning side effect occurs
