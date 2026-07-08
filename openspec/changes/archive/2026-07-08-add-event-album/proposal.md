## Why

When two people join an event, each device ends up with the event's photos scattered into its camera
roll with no grouping — your own contributions mixed into your timeline and the photos you received
from others landing loose alongside them. There is no "this is event X" collection on the device.
Users want the received-and-contributed set gathered into **one named album per event**, mirroring the
shared event locally. The join surface was explicitly built to grow this: `StatusScreen.kt` carries
the reserved slot *"Future options (albums, save-to album) slot in as rows,"* so this is the intended
next join option, not a new subsystem bolted on.

## What Changes

- Add a **join-time "Save event photos to an album" checkbox**, chosen once on the join confirmation
  surface, **default unchecked** (opt-in), offered in **all three** participation directions. Like the
  cutoff and direction options it is **fixed for the membership** — to change it the user leaves and
  rejoins.
- When checked, the app **creates one PhotoKit album titled after the event** and every synced photo
  for that membership — **both the foreign photos it downloads and its own photos it uploads** — is
  placed into that album (best-effort).
- The **app is the sole album creator**: it creates the album eagerly on the photo-permission
  `→ GRANTED` transition (or right at join if already granted), reusing the existing grant collector.
  Because sync cannot happen without that same permission, the album always exists before the first
  synced photo, so **both the app and the upload extension only ever _add_, never create** — no
  cross-process create race.
- **Downloaded** photos are added **atomically inside the importer's existing `performChanges`
  commit** (same commit as asset creation, so a received photo is never briefly loose).
  **Uploaded** photos are added at **upload-cycle completion, in whichever process ran the cycle** —
  the extension on iOS ≥26.1, the app on 18–26.0 — recovering the asset's raw `localIdentifier` by
  reversing the `normalizeAssetId` `/`→`_` mapping and re-fetching the `PHAsset` (best-effort: a
  fetch-miss for a deleted/exotic asset simply skips).
- The **album identity is remembered per event** in a small `eventId → albumLocalId` store that
  **survives `LeaveEvent.leave()`'s `config.clear()`**, so a **re-join reuses the same album**
  (history preserved). If the stored album no longer resolves (user deleted it), a re-join with the box
  checked creates a fresh one; within a membership a deleted album is not recreated.
- **Tighten the event name to non-nullable** end-to-end (`EventDetails.Found.name`, `EventConfig.name`,
  `CreateOutcome.name`): a loaded event always carries a name to title the album; a `200` details
  response lacking a name becomes a transient `Failed` (retryable), not a `Found` with a null name. The
  **backend already enforces name-required on create** (`event-creation`: trim, reject empty), so this
  is a **client-only** tightening — no backend change.
- Add an optional **dev/test `saveToAlbum` override** to the deeplink wire payload (additive within
  `v=3`, never emitted by the canonical encoder), so the headless `autoJoin` / harness path can force
  album-on; `autoJoin` defaults to **off**.
- The creator path is unaffected structurally — create-event auto-routes into the **same** join
  confirmation surface, so the one checkbox covers create-join and scan-join.

## Capabilities

### New Capabilities

- `event-album`: the album-mirroring capability — the opt-in semantics, the app-as-sole-creator
  lifecycle (create on permission-grant, reuse-on-rejoin via a leave-surviving `eventId → albumLocalId`
  map, dangling-recreate), the `AlbumManager` / `AlbumMapStore` platform seams, a tested `commonMain`
  `AlbumCoordinator` (resolve-or-create, dispatch/skip), and the contract that every synced photo
  (downloaded **and** uploaded) is placed best-effort — download atomically in the importer,
  upload at cycle completion in whichever process ran it, using the reversed raw `localIdentifier`.

### Modified Capabilities

- `join-event`: the join confirmation surface gains the **save-to-album checkbox** (default unchecked,
  all directions); the choice threads through confirm → `JoinEvent.join` → provision and is persisted;
  `autoJoin` defaults album-off and honors the dev override; the details gate treats **name as
  required** (a `200` without a name is `Failed`, not `Found`).
- `deeplink-config`: `EventConfig` gains `saveToAlbum: Boolean = false` (defaulted for back-compat with
  already-persisted JSON) and its `name` becomes **non-nullable**; `EventLinkPayload` gains an optional
  `saveToAlbum` dev/test override key (additive within `v=3`, absent by default).
- `photo-download`: the importer, when the joined membership opted into an album, **adds the
  newly-created asset to the event album in the same `performChanges` commit** as its creation
  (atomic), sourcing the album id from the shared map; the reconcile/import path is otherwise unchanged.

## Impact

- **New capability module** (`:capability:album` or folded into an existing iOS-adjacent module): the
  `AlbumManager` + `AlbumMapStore` seams (`commonMain`), the tested `AlbumCoordinator`, the iOS
  `PHAssetCollection` impl (`iosMain`, wiring-only/untested), and the shared (App-Group/Keychain)
  album-map store readable by both the app and the extension process.
- **Config** (`:capability:config`): `EventConfig` gains `saveToAlbum` and a non-null `name`;
  `EventLinkPayload` gains the optional override; the deeplink codec accepts it; whole-object flow means
  no port changes and **no migration** (a legacy config without `saveToAlbum` decodes to `false`).
- **Join** (`:capability:join-event`, `:domain:ui`, `:domain:presentation`, `:capability:event-creation-ui`):
  a checkbox row (new `App*` component) on `JoiningEventScreen`; `saveToAlbum` plumbed through
  `onConfirm*` / `commitJoin` / `JoinEvent.join` / `provision`; `EventDetails`/`CreateOutcome` name
  types tightened.
- **Download** (`:capability:download`): `IosPhotoLibraryImporter` gains an injected album-id lookup
  and adds the created asset in its existing commit; `DownloadController` is unchanged (pure).
- **Upload** (`:capability:upload`, `:app:ios:photokit-extension`, `:app:ios:url-session-upload`): a
  completion-time album-add invoked from the `UploadCycle` drain in **both** roots (extension on
  ≥26.1 — **verified on-device by spike: the extension can create + mutate a `PHAssetCollection`** —
  and the app-driven URLSession tier on 18–26.0), recovering the raw `localIdentifier`.
- **iOS wiring** (`:app:ios`): the app becomes the album creator via the existing
  `enableBackgroundUploadOnGrant` grant collector (a sibling ensure-album-on-grant) and `provisionEvent`.
- **No backend, engine, or ledger changes.** The backend already enforces name-required; the ledger
  schema is untouched (raw ids are recovered by reversal, not stored). No data migration.
