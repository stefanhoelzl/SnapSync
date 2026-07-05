## Why

The push-notification infrastructure is in place end-to-end (registration, `/notify` fan-out, silent
`apns-push-sender`, a `PushReceiver` seam), but nothing yet **calls** `/notify` and the receiver is a
no-op logger. So foreign photos are still discovered only on foreground entry (`photo-download`'s
"foreground-only discovery") — a device that never opens the app misses a co-contributor's uploads
until its next foreground visit. This change closes the loop: an uploading device pokes `/notify`
after its uploads settle, and every member device wakes in the background to pull the new photos.

## What Changes

- **Notify on upload settle.** After an upload cycle **fully drains with ≥1 completion**, the uploader
  fires a best-effort `POST /event/<eventId>/notify`. This is fired from the shared, platform-agnostic
  `UploadCycle` (so both the iOS ≥26.1 extension tier and the iOS 18–26.0 url-session tier get it) at
  the drained-cycle point **after** the device-manifest PUT — the only moment the backend union
  actually reflects the just-completed assets. New `EventNotifier` (pure Kotlin, injected HTTP seam,
  no body, no retry, failures swallowed), mirroring `PushRegistration`.
- **Silent push carries the event id.** The fixed silent payload gains a top-level `eventId`
  (`{"aps":{"content-available":1},"eventId":"<uuid>"}`), sourced from the notify route's path. No
  caller payload is introduced (the endpoint stays payload-free; the id is server-chosen from the
  path).
- **Real, guarded push receiver.** `LoggingPushReceiver` is replaced by a receiver that runs
  `DownloadController.reconcile(eventId)` — but **only if the pushed event id equals the device's
  active event** (else no-op). This prevents a locally-left event (whose backend manifest persists, so
  it keeps being pushed) from silently re-pulling new photos, and handles the no-active-event case.
- **Receive path holds the OS fetch handler.** The receive seam becomes async: the app-shell awaits
  reconcile's synchronous portion (union read + enqueue) before calling the OS
  `fetchCompletionHandler`, so iOS keeps the app alive long enough to enqueue the background transfers.
- **Push-triggered background discovery.** `photo-download` gains a background discovery trigger
  (guarded on the active event), relaxing its "foreground-only" rule; foreground/join discovery
  remains the backstop.

## Capabilities

### New Capabilities
- `upload-completion-notify`: after a fully-drained upload cycle that recorded ≥1 completion, fire a
  best-effort event notify (`POST /event/<eventId>/notify`) so co-contributors are woken; the trigger
  lives in the platform-agnostic upload cycle and fires after the in-cycle device-manifest PUT.

### Modified Capabilities
- `apns-push-sender`: the fixed silent payload now includes a top-level `eventId` (the notify route's
  event id); the body is otherwise unchanged (`content-available: 1`, `background`, priority 5).
- `event-notify-endpoint`: the dispatched push now carries the route's `eventId` in its payload (still
  no caller-supplied payload; the id is taken from the path).
- `push-registration`: the silent-push receive seam gains the pushed `eventId` and an async completion
  contract, and its wired implementation becomes a **guarded** receiver (reconcile only when the pushed
  id is the active event) replacing the infrastructure-phase logging no-op.
- `photo-download`: discovery is no longer foreground-only — a silent push for the active event
  triggers a background `reconcile` (union read + enqueue + import) within the push's execution window;
  foreground/join discovery remains the backstop.

## Impact

- **Code:** `:capability:upload` (notify seam fired from `UploadCycle`); `EventNotifier` in
  `:capability:push`;
  `:capability:push` (receive seam signature, guarded receiver); `:capability:download` (wiring the
  guarded receiver to `DownloadController` + an active-event provider); `:app:ios` composition root
  (bind the notifier into both roots, wire the async receive seam) and the thin Swift AppDelegate
  (extract `userInfo["eventId"]`, await the receiver before the OS handler); backend (`apns-push-sender`
  payload, `event-notify-endpoint` dispatch). Apply also brought the url-session tier
  (`UrlSessionUploadController`, iOS 18–26.0) to union parity — wiring its previously-missing
  device-manifest PUT + echo-suppression alongside the notify — so its uploads are discoverable and its
  notify is meaningful.
- **Tests:** new `commonTest` coverage (JVM + simulator) for `EventNotifier`, the notify-on-drain
  firing in `UploadCycle`, and the guarded receiver; backend tests for the eventId in the push payload.
- **Behavior:** foreign photos now arrive without a foreground visit (best-effort — silent pushes are
  throttled/coalesced by iOS, so foreground discovery stays the guaranteed backstop).
- **No new dependencies.** Reuses the shared Ktor/Darwin client and existing background-download/import
  machinery.
