## ADDED Requirements

### Requirement: Developer launch-environment LOG-EXPORT trigger

The iOS app SHALL read a `SNAPSYNC_EXPORT_LOGS` variable from the process environment **once per
process launch**. Its **presence** (any value) SHALL copy the upload extension's log — `ext-debug.log`
in the shared App Group container, and its rolled `.1` sibling when present — into the app's own
`Documents/`, where `pymobiledevice3 apps pull` can reach it.

The trigger exists because the extension's log lives in the App Group (capability
`diagnostic-logging`, so the app process can read it for a diagnostic dump) and an App Group
container is **not** USB-pullable, while the extension itself can never observe a launch environment
variable — the OS launches it. The app is therefore the only process that can perform the copy.

The copy SHALL happen at boot. It therefore yields the extension's history up to its most recent
invocation, which is the whole of it: the extension is not running while an operator pulls.

The trigger SHALL be **independent of the membership-mutating triggers**: it mutates no membership,
participates in no ordering with `reset → leave → create → event-link`, and SHALL apply on a
`SNAPSYNC_FORGE_STATE` launch as well, since copying a file reaches no live-stack seam.

The trigger SHALL be applied **at most once per process** and SHALL rely on the
developer-launch-only injectability of a process-environment variable, so it is inert in production
**with no compile-time guard**, exactly as its siblings are.

#### Scenario: Present variable exports the extension log
- **WHEN** the app is launched with `SNAPSYNC_EXPORT_LOGS` present on a device whose extension has logged
- **THEN** `ext-debug.log` (and its `.1` sibling when present) is copied into the app's `Documents/`,
  and `pymobiledevice3 apps pull app.snapsync Documents/ext-debug.log` returns it

#### Scenario: Export with no extension log is a no-op
- **WHEN** the app is launched with `SNAPSYNC_EXPORT_LOGS` present on a device where the extension has never run
- **THEN** nothing is copied and no error surfaces

#### Scenario: Export applies on a forge launch
- **WHEN** the app is launched with both `SNAPSYNC_EXPORT_LOGS` and a recognized `SNAPSYNC_FORGE_STATE`
- **THEN** the copy is performed and the forged frame renders, provisioning nothing

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_EXPORT_LOGS` in its environment
- **THEN** no copy occurs and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build
