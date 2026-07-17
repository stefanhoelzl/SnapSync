# ios-url-session-upload — delta for establish-shared-composition

## MODIFIED Requirements

### Requirement: The app-driven cycle skips on an unreadable membership

The app-driven tier SHALL reach its cycle-entry decision through the three-state membership read
(capability `event-link`) and the shared decision function (capability `upload-lifecycle`). It SHALL NOT
reach it through the two-state config state flow, which cannot express "unreadable" and reports it as
`null` — indistinguishable from a leave.

The tier SHALL NOT carry a cycle-entry translation of its own: its cycle is assembled by the shared
composition `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"),
whose entry gate is port-pure — one fresh `ConfigReader.read()` per cycle, the identity probe, and the
host — per `upload-lifecycle` "The upload cycle owns its entry decision". The tier's former
controller-local gate additionally refreshed the UI-facing config `StateFlow` each cycle; that side
effect is not part of the entry gate and is owned by the app shell's protected-data unlock hook
(decision record: `changes/archive/establish-shared-composition` D1).

This tier invokes its own cycles from the app process, from four triggers (start, foreground, background
task, session events) plus silent push. Each SHALL produce **Skip** on an unreadable membership: no
reconciliation, no `joinedEventId` marker clear, no discovery-cursor reset, no upload job. The exposure is
narrow — the membership item is stored `AfterFirstUnlock`, so an unreadable read needs a boot with no
unlock — and the requirement stands regardless: the accessibility attribute makes a false leave
improbable, the three-state read makes it impossible.

The tier SHALL probe the device identity per cycle rather than resolving it once into a held value. A held
identity cannot express "unreadable this cycle": an unresolvable identity throws out of whatever first
touches it instead of skipping cleanly. The probe is per-process in effect on both tiers already — the
identity caches for the process lifetime, and the OS-invoked tier's per-cycle probe is per-process because
its process dies each cycle.

#### Scenario: A background task on an unreadable membership does not leave the event
- **WHEN** the app-driven tier runs a cycle from its background task and the membership read fails because
  protected data is unavailable
- **THEN** the cycle skips, the `joinedEventId` marker is intact, and the device is still joined on the
  next readable cycle

#### Scenario: An unresolvable device identity skips rather than throwing
- **WHEN** the app-driven tier runs a cycle and the device identity cannot be resolved
- **THEN** the cycle skips cleanly and no error escapes the cycle

#### Scenario: A definitely-absent membership still clears the marker on this tier
- **WHEN** the app-driven tier runs a cycle after a leave, and the membership read reports no item
- **THEN** the leave-side reconciliation runs and the `joinedEventId` marker is cleared

#### Scenario: The tier's cycle is the shared composition
- **WHEN** `UrlSessionUploadController` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no gate, cycle, reconciler, or
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic`'s
  `HttpEnrollment`

### Requirement: Module placement and testing split

The app-driven adapters (`IosUrlSessionUploadPlatform`, `IosBackgroundScheduler`) SHALL live in the
app-only adapter module `:adapter:ios:app-only` — linked only by the main app process, never the
extension (before migration step 4 they lived in `:app:ios:url-session-upload`, deleted by that
step) — depending on the extension-safe adapter module `:adapter:ios:ext-safe` for the shared
`IosDiscovery` walk. The
`BackgroundUploadPump` and `BackgroundScheduler` pump logic SHALL live in `:domain` — the pump in
`feature/upload`, the scheduler seam in `ports/` (seated by migration step 5; formerly
`:capability:upload`) — `jvm()`-enabled and harness-covered. The pump and scheduler logic SHALL be
tested on JVM and
`iosSimulatorArm64`; the `URLSession` adapter SHALL be faked in the harness (like the PhotoKit
adapter). Because a background `URLSession` runs in the iOS simulator, the transport MAY be exercised
end-to-end in the simulator; `BGProcessingTask` **timing** remains device-only.

#### Scenario: Pump lives in the platform-free core
- **WHEN** the modules are assembled
- **THEN** `BackgroundUploadPump` is in `:domain` `feature/upload`, and the iOS adapters are in `:adapter:ios:app-only`, which composes `:adapter:ios:ext-safe`; the pump and the `uploadCore`-assembled cycle are composed with the adapters in the app's composition root, not by the adapter module
