## Why

Joining an event is currently silent and instantaneous: scanning a QR (or opening a
`snapsync://` deeplink) immediately persists the config and enables uploads, with no chance for
the user to confirm — and with no server-side record that the device has joined until its first
upload cycle happens to write a manifest. The user wants (1) an explicit **confirmation** before a
join takes effect, and (2) that confirmation surface to be built so the **future join options**
(start date, upload/download direction, album selection, save-to album) can be added as it grows —
so a bare `AppConfirmDialog` like the leave flow is deliberately not enough.

## What Changes

- **A join-confirmation gate** replaces silent auto-provisioning. A decoded deeplink now opens a
  dedicated **full-screen "Join event"** surface instead of provisioning directly.
- **Event details are fetched and gate the confirm.** On open, `GET /events/:id` runs behind a
  "Loading event details…" state; a **404 blocks** the join (invalid/expired invite), a
  network/502 shows **Retry**, and success shows the event name + **Join / Cancel**.
- **Confirming enrolls, then provisions.** Confirm does an **enrollment PUT** (writes a
  register-only **empty** device manifest so the device becomes an enumerable, notifiable member
  *immediately* — before any upload) and only on success saves config + enables uploads. A failed
  PUT keeps the user on the surface with an error + Retry; nothing is persisted.
- **Switching events** (scanning a new event while already joined) shows a leave-style confirm and,
  on confirm, runs **leave then join** — composing the existing `LeaveEvent` use-case (never
  editing it), so it inherits the parallel workspace's backend-leave call for free when that lands.
  A join that fails after a successful leave is a transient no-event state recoverable via Retry.
- **A dev/test `autoJoin` flag** is added to the deeplink payload. When set, the gate runs
  identically but **auto-confirms** once details load (still GETs, still enrolls) — keeping the
  headless on-device build loop working, since it cannot tap a Join button.
- **BREAKING (dev only):** the deeplink payload gains an optional `autoJoin` key; a link carrying
  it is rejected by pre-change builds (strict decoder). Real invite QRs never emit it, so no
  production link is affected.

## Capabilities

### New Capabilities
- `join-event`: the app-side join lifecycle — the decode→details→confirm gate, the `JoiningEvent`
  UI-state family and full-screen join screen, the switch confirmation, the `JoinEvent` use-case
  (GET details → register-only enrollment PUT → provision), the leave∘join switch composition with
  retry-on-partial-failure, and the `autoJoin` auto-confirm behavior.

### Modified Capabilities
- `deeplink-config`: the payload contract loosens from **exactly** `eventId` to `eventId` **plus an
  optional `autoJoin` boolean**; the structural decoder accepts `autoJoin` as a known key (still
  rejecting genuinely-unknown keys) and its success result carries the flag.
- `sync-status-screen`: the `UiState` families admit a new **`JoiningEvent`** family (owned by
  `join-event`), mirroring how the create layer is owned by `event-creation-ui` yet reduced here.
- `ios-app-shell`: the container's `onOpenUrl` routes a decoded deeplink to the join gate instead of
  saving immediately; the `SNAPSYNC_DEEPLINK` dev trigger's link carries `autoJoin=true` so it still
  provisions headlessly (auto-confirmed) rather than parking on an un-tappable confirm screen.

## Impact

- **Code:** new `join-event` capability module (`JoinEvent` use-case + join screen + `JoiningEvent`
  state); `:capability:config` (`EventLinkPayload.autoJoin`, decoder, `ConfigDecodeResult.Success`);
  `:domain:presentation` (`UiState.JoiningEvent`, `Joined.pendingSwitch`, container gate + intents);
  `:domain:ui` (full-screen join screen, switch dialog); `:app:ios` `SnapSyncRoot.onOpenUrl` reroute
  and the `SNAPSYNC_DEEPLINK` link.
- **Backend:** no new endpoints — reuses `GET /events/:id` (details/existence) and
  `PUT /events/:id/devices/:deviceId` (enrollment). The membership/notify fan-out already enumerates
  members by that manifest, so an empty-manifest device becomes reachable at once.
- **Dependencies / coordination:** composes `leave-event` without editing it. The parallel workspace
  that adds a backend leave call owns that spec's change; the switch path picks it up on merge.
- **Tests:** `:test:integration` seam→UI-state coverage of the gate (loading/404/retry, confirm→
  enroll→provision, switch leave∘join, autoJoin auto-confirm), plus `:capability:config` decoder
  tests for `autoJoin`.
