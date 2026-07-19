# push-registration — delta for foreground-poll-and-swift-transcriber

## MODIFIED Requirements

### Requirement: Silent-push receive seam

`:domain`'s `ports/` SHALL define the `PushReceiver` seam invoked when the app receives a silent
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
The guard + reconcile logic SHALL live in a **tested** feature (`DownloadPushReceiver`, `:domain`
`feature/download`, exercised on JVM and `iosSimulatorArm64`), never parked in the untested app shell.
The cross-arm **fan-out** — one push is news to both arms — SHALL be the `flow/SilentPush` trigger
(`:domain` `flow/`, built in `compose/`; it absorbed the former `FanOutPushReceiver`): the receivers run
in order (download, then the upload arm's on the app-driven tier), isolated so one failure never robs
the other of the scarce wake. The flow SHALL take the OS payload **whole** (migration step 12, the
transcriber law): the app-shell wiring forwards the raw `userInfo` dictionary and the completion
handler; the `eventId` extraction is the tested `model/` payload codec (`pushEventId`), applied
inside the flow — a payload with no usable `eventId` fans out to no arm, and the completion handler
is released either way. Before fanning out, the flow SHALL re-read the persisted membership into
the config StateFlow (the trigger-time `reloadConfig` re-read — see `ios-app-shell`), so the
receivers' active-event guards read current state.

#### Scenario: A push for the active event reconciles

- **WHEN** a silent push carrying `eventId` = the device's active event is delivered and routed to
  `PushReceiver`
- **THEN** the receiver runs `reconcile(eventId)` (discover foreign assets, enqueue downloads, import
  staged assets)

#### Scenario: A push for a non-active or left event is a no-op

- **WHEN** the pushed `eventId` differs from the active event, or no event is configured
- **THEN** the receiver performs no reconcile (a left event's persisting membership cannot re-pull
  photos)

#### Scenario: A payload without an eventId wakes no arm

- **WHEN** a silent push arrives whose `userInfo` carries no usable top-level `eventId` string
- **THEN** the flow fans out to no receiver, logs the miss, and the OS completion handler is still
  released

#### Scenario: The receive path awaits before the OS handler

- **WHEN** a silent push for the active event is handled
- **THEN** the app-shell signals the OS background-fetch completion handler only after the flow's
  synchronous portion (payload decode, membership re-read, attestation wake) has completed — the
  fan-out's reconcile escapes on the app scope, and the enqueued background transfers then continue
  on their own

#### Scenario: The receiver is replaceable

- **WHEN** a future change supplies a different `PushReceiver` implementation
- **THEN** it is invoked on receipt with the pushed `eventId` and no change to the app-shell receive
  wiring
