## Why

The status screen was built for a *personal-backup* mental model: a permission **gate** that
replaces the whole screen until access is granted, and raw upload **counts** ("n of N images
synced"). But the product is **sharing event photos with others**. The screen should be
invite-first, tell the user at a glance whether things are healthy, and treat missing permission as
an inline nudge — not a wall that hides the invite. This change refocuses the whole joined
experience on that: scan/create → land on a shareable event → one calm health line → done.

## What Changes

- **Joined layer = "config present."** The permission full-screen gate is removed. Once an event is
  configured, the screen **always** shows the event home (name · QR · share · leave), and permission
  becomes one **status-line** state rather than a hero-replacing screen. **BREAKING** to the current
  `UiState` shape (see Modified Capabilities).
- **One-line health status, no counts.** The upload/download counts are replaced by a single status
  line: `✓ In sync` (settled) / `↑↓ Syncing…` (work remaining) / `⚠ Turn on photo access ›`
  (permission off — the sole attention state). Copy is "sync/share", never "back up".
- **Two symmetric, honest sync arrows.** Each direction is **shown by completeness** and **pulses by
  live activity**: `↑ shown ⇔ completed < total`, `↑ pulse ⇔ pending > 0`;
  `↓ shown ⇔ downloaded < total`, `↓ pulse ⇔ inFlight > 0`. This requires a new **download in-flight
  signal** (the `↓`-pulse analogue of the upload ledger's `pending`).
- **Invite & leave key off config-present, not permission.** The QR, share, and leave affordances now
  render whenever an event is configured — **including when permission is off** (sharing the invite
  needs no photo access). The leave confirmation copy becomes "Leave this event?" / **Stay** / **Leave**.
- **Event name shown to everyone.** The event's name is displayed as the screen title. The name is
  **not** carried in the QR; a joined device fetches it from the existing `GET /event/:id` (scan path
  only — create already receives it from `POST /event`). Persisted config gains the name.
- **Config type split for clarity.** `EventConfigPayload` (overloaded as both the QR wire format and
  the persisted config) splits into `EventLinkPayload { eventId }` (deeplink wire format, unchanged
  `v=3`) and `EventConfig { eventId, name? }` (persisted joined-event state).
- **Visual refresh.** A green brand identity, flat status line (background only on the attention
  state), flat icon actions, and a dark theme (QR stays a light "pass" card so it always scans).

Out of scope / unchanged: in-app QR scanner (deferred), date-scoped upload (stays whole-library),
multi-event, the deferred "waiting for the system" staleness state, and any backend change
(`GET /event/:id` already exists).

## Capabilities

### New Capabilities
<!-- none — the download in-flight signal extends existing sync-status / download-store specs -->

### Modified Capabilities

- `sync-status-screen`: `UiState` collapses — the joined `InProgress`/`Completed`/`NothingToSync`
  and `PermissionBlocked` states become a single joined state carrying a **health descriptor**
  (`InSync` / `Syncing(up, down)` / `NeedsAccess(permission)`); counts are no longer rendered; the
  event **name** is a screen param; leave copy changes.
- `permission-gate`: reframed — permission renders as a **status-line variant inside the joined
  layer**, never a hero-replacing gate. The permission **domain contracts** (`PermissionStatus`,
  ports, iOS adapter, liveness) are unchanged.
- `event-invite-qr`: **reversal** — the QR, caption, and share affordances render whenever config is
  present (the joined layer), **including when permission is not `GRANTED`**. The invite URL is
  derived via `encodeConfigUrl(EventLinkPayload(config.eventId))`.
- `leave-event`: the leave affordance is scoped to config-present (the new joined layer); the
  confirmation copy is "Leave this event?" with **Stay** / **Leave**.
- `sync-status`: `DownloadProgress` gains an `inFlight` count (the `↓`-pulse signal), the analogue of
  `SyncProgress.pending`; the screen folds upload + download into one indicator.
- `download-store`: the store records a per-resource **enqueued-not-staged** marker (a download task
  sent to the OS) and exposes an `inFlightCount()` read.
- `deeplink-config`: the deeplink payload type is renamed `EventConfigPayload → EventLinkPayload`
  (wire format unchanged: `snapsync://config?v=3&d=<base64url({eventId})>`).
- `event-creation-ui`: create-screen copy is reworded to the sharing framing; a successful create
  saves an `EventConfig` carrying the returned name.
- `deeplink-config` / persisted config: introduce `EventConfig { eventId, name? }` as the persisted,
  joined-event state (retyping `ConfigStore`/`ConfigSource`); a scan-provision fetches the name from
  `GET /event/:id` best-effort (never blocking the join) and a foreground refresh keeps it current.
- `design-system`: a semantic status-line component, flat icon buttons, the green skin, and dark-theme
  support (QR rendered on a light card in both themes).

## Impact

- **Modules:** `:domain:presentation` (UiState + reduction + new screen params), `:domain:ui` +
  `:domain:ui:components` (event-home re-layout, status-line component, flat icons, green/dark skin),
  `:domain:status` (`DownloadProgress.inFlight`), `:capability:config` (`EventConfig` /
  `EventLinkPayload` split, name persistence, `GET /event/:id` fetch), `:capability:download` /
  `:capability:download-store` (enqueued marker + in-flight count), `:capability:event-creation-ui`
  (copy + save-with-name), `:capability:rejoin` (leave copy path).
- **APIs:** consumes the existing `GET /event/:id` backend route (no backend change). No deeplink
  wire-format change.
- **Tests:** reducer/classification tests for the collapsed `UiState`; `DownloadStore`
  in-flight-count contract; status-line direction/pulse derivation; deeplink codec against the renamed
  type; Compose UI tests for the event-home layout and status-line states.
- **Harnesses:** both desktop harnesses (`:app:desktop:ui:run`, `:app:desktop:run`) need their
  presets/panels updated for the new `UiState` and the download in-flight signal.
