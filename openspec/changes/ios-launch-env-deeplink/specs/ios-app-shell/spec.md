## ADDED Requirements

### Requirement: Developer launch-environment config trigger

The iOS app SHALL read a `SNAPSYNC_DEEPLINK` variable from the process environment **once per
process launch** and, when it is present and holds a valid `snapsync://config?…` URL, provision the
event **identically to a scanned deeplink** — forwarding the raw URL string to
`SnapSyncRoot.onOpenUrl(_:)`, which performs the authoritative decode/validate and, on success,
re-provisions (clear ledger + discovery cursor, re-register the background-upload extension). The
read SHALL reuse the existing `deeplink-config` decoder and the `onOpenUrl` path verbatim; it SHALL
NOT introduce a second decoder or config-construction path, and SHALL perform no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose view or
view-controller recreation within the same process. A subsequent **cold launch** with the variable
still set SHALL re-provision again (the intended per-build re-trigger).

When the variable is **absent**, the app SHALL behave exactly as without this feature (no
provisioning side effect). The trigger SHALL rely on the fact that a process-environment variable is
only injectable via a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`); launches
from SpringBoard or TestFlight carry no such variable, so the trigger is inert in production **with
no compile-time guard**. When the variable is present but holds an invalid or non-`snapsync://`
value, the app SHALL produce no provisioning side effect (the existing decoder rejects it).

#### Scenario: Cold launch with the variable provisions once
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a valid `snapsync://config?v=3&d=…`
  URL
- **THEN** the app provisions that event exactly as a scanned QR would — clearing the ledger and
  discovery cursor and re-registering the background-upload extension — and forcing a view/view-controller
  recreation within that same process does not re-apply the trigger or re-clear the ledger

#### Scenario: A subsequent cold launch re-triggers
- **WHEN** the app is launched again in a fresh process with `SNAPSYNC_DEEPLINK` still set
- **THEN** the app re-provisions (re-clears the ledger and re-registers the extension), so an agent
  can drive a fresh per-build upload by relaunching with the variable set

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight, with no `SNAPSYNC_DEEPLINK` in its
  environment
- **THEN** no provisioning side effect occurs and behavior is identical to the app without this
  feature, with no compile-time flag distinguishing the build

#### Scenario: Invalid environment value is rejected
- **WHEN** the app is cold-launched with `SNAPSYNC_DEEPLINK` set to a malformed or non-`snapsync://`
  value
- **THEN** the existing decoder rejects it and no provisioning side effect occurs
