## Why

Today a joined device is always a full bidirectional participant: it uploads its own photos **and**
imports every other contributor's photos, with no way to opt out of either direction short of not
joining at all. Some users want to **contribute without receiving** (upload-only) or **receive without
contributing** (download-only). The join surface was explicitly built to grow this option — its code
carries the reserved slot *"Future options (direction, albums, save-to album) slot in as rows"* — so
this is the intended next step, not a new subsystem.

## What Changes

- Add a **join-time participation direction** chosen once on the join confirmation surface, with three
  modes: **Both** (default, today's behavior), **Upload only**, and **Download only**. The choice is a
  three-way segmented control and is **fixed for the membership** — to change it the user leaves and
  rejoins (or switches).
- Persist the chosen direction in `EventConfig` (alongside `eventId`/`name`/`minPhotoDate`), flowing
  whole-object through the existing persistence, serialization, and read paths.
- **Upload only** disables the download machinery: the download reconcile is skipped for that
  membership at every trigger (join, foreground, silent push).
- **Download only** does not enable the background-upload producer, but the device still **enrolls**
  (empty manifest) — it counts as an event member and keeps the event alive while it consumes.
- Status screen: **silently mask** the opted-out direction's arrow; `InSync` is computed over the
  enabled direction(s) only. No new status vocabulary and no mode label.
- The capture-date cutoff row (an upload-only concept) is **shown-but-disabled** under Download-only.
- Reword the permission priming copy from "back it up" to sync/share framing (extending the existing
  sharing-framing requirement to the permission surface).
- Add an optional **dev/test `direction` override** to the deeplink wire payload so the headless
  `autoJoin` path can exercise each mode on device; `autoJoin` still defaults to **Both**.
- The creator path is unaffected structurally — creating an event auto-routes into the **same** join
  confirmation surface, so the segmented control covers create-join and scan-join with one code path.
- The **backend is unchanged** (uploads stay ungated, the union read stays identity-blind and
  marker-gated, push stays ignored client-side when download is disabled).

## Capabilities

### New Capabilities

_None — this extends existing capabilities; the join surface already reserved this option._

### Modified Capabilities

- `deeplink-config`: add a `direction` field to the persisted `EventConfig` (defaulted for back-compat
  with already-persisted JSON) and an optional `direction` key to the `EventLinkPayload` wire payload
  (a dev/test override, additive within `v=3`, absent by default — mirroring `minPhotoDate`).
- `join-event`: the join confirmation surface gains a direction segmented control; the chosen
  direction threads through confirm → `JoinEvent.join` → provision and is persisted; the
  background-upload producer is enabled **only** when the direction includes upload; the cutoff row is
  disabled under Download-only; `autoJoin` defaults to Both and honors the dev override.
- `photo-download`: the download reconcile is **gated on the persisted direction** — a membership
  whose direction excludes download performs no reconcile at any trigger.
- `sync-status-screen`: the joined-layer health masks the opted-out direction's arrow and computes
  `InSync` over the enabled direction(s) only; the permission priming copy is reworded to sync/share
  framing.

## Impact

- **Config** (`:capability:config`): `EventConfig` + `EventLinkPayload` gain a `direction`; the
  deeplink codec accepts the optional override key; the Keychain store and legacy-upgrade path rely on
  the field default (no port changes — everything is whole-object).
- **Join** (`:capability:join-event`, `:domain:ui`, `:domain:presentation`): a segmented row on
  `JoiningEventScreen`; `direction` plumbed through `onConfirm*` / `commitJoin` / `JoinEvent.join` /
  `provision`; producer-enable in `provisionEvent` gated on direction.
- **Download** (`:capability:download`): `DownloadController.reconcile` gains a direction gate (the
  single choke point for all triggers), keeping the skip logic in a tested capability rather than the
  untested app shell.
- **Status** (`:domain:presentation`): `syncHealth` takes the direction and HIDDEN-masks the excluded
  arrow; `sync-status`/`SyncProgress`/`DownloadProgress` vocabulary is unchanged.
- **iOS wiring** (`:app:ios`): thread `direction` through the composition-root bindings and the
  headless `autoJoin` override; no new platform machinery.
- **No backend, engine, or ledger changes.** No migration — an already-persisted `EventConfig`
  without `direction` decodes to the default (Both).
