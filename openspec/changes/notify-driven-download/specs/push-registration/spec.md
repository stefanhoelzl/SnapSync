## MODIFIED Requirements

### Requirement: Silent-push receive seam

The module SHALL define a `PushReceiver` seam invoked when the app receives a silent
(`content-available`) push, carrying the pushed **`eventId`** (delivered as the push payload's
top-level `eventId` key, capability `apns-push-sender`). The seam SHALL be **asynchronous**
(suspending / completion-shaped) so the app-shell can **await** the receiver's synchronous work (the
union read + download enqueue) **before** signalling the OS background-fetch completion handler —
keeping the app alive through the push's execution window rather than risking suspension before any
transfer is enqueued. The wired implementation SHALL trigger download discovery
`reconcile(eventId)` (capability `photo-download`) **guarded** on the pushed `eventId` equalling the
device's **active event** (from the config seam): a push whose `eventId` is not the active event —
**including when no event is configured** — SHALL be a **no-op** (no reconcile). The guard exists
because leave is local-only (capability `leave-event`): a left event's backend membership persists and
keeps pushing this device, so an unguarded reconcile would silently re-pull a left event's new photos.
The guard + reconcile logic SHALL live in a **tested** module (exercised on JVM and
`iosSimulatorArm64`), never parked in the untested app shell; the app-shell wiring only forwards the
OS callback (the raw `eventId` and the completion handler) into the seam.

#### Scenario: A push for the active event reconciles

- **WHEN** a silent push carrying `eventId` = the device's active event is delivered and routed to
  `PushReceiver`
- **THEN** the receiver runs `reconcile(eventId)` (discover foreign assets, enqueue downloads, import
  staged assets)

#### Scenario: A push for a non-active or left event is a no-op

- **WHEN** the pushed `eventId` differs from the active event, or no event is configured
- **THEN** the receiver performs no reconcile (a left event's persisting membership cannot re-pull
  photos)

#### Scenario: The receive path awaits before the OS handler

- **WHEN** a silent push for the active event is handled
- **THEN** the app-shell signals the OS background-fetch completion handler only after the receiver's
  synchronous portion (union read + enqueue) has completed, so the enqueue is not cut short by
  suspension

#### Scenario: The receiver is replaceable

- **WHEN** a future change supplies a different `PushReceiver` implementation
- **THEN** it is invoked on receipt with the pushed `eventId` and no change to the app-shell receive
  wiring
