## Context

An event is a multi-device sharing unit: a joined device uploads its own photos (the app-driven
`URLSession` tier on iOS 18–26.0, or the OS-driven PhotoKit extension on ≥26.1) and downloads every
other contributor's photos (`DownloadController` → `IosPhotoLibraryImporter`, app-side). Today all of
those photos land loose in the camera roll with no per-event grouping. **No PhotoKit album code exists
anywhere** — the app works purely at the asset/resource level; `docs/design.md` explicitly deferred
"album selection" and "album membership." This change is the first `PHAssetCollection` work.

The join confirmation surface was built to grow exactly this: `StatusScreen.kt` carries the reserved
slot *"Future options (albums, save-to album) slot in as rows,"* and `EventConfig` flows
**whole-object** end-to-end (a warning comment at `SnapSyncRoot.kt` guards against destructuring it, so
new fields auto-propagate through the Keychain store and the extension read). `minPhotoDate` and
`direction` are the precedents for a per-membership option chosen at join, persisted on `EventConfig`,
and carried as an optional dev override on `EventLinkPayload`.

All forks were resolved in a pre-proposal interview and one on-device spike (below).

## Goals / Non-Goals

**Goals:**
- Let a user opt in, at join, to gather an event's photos (downloaded **and** uploaded) into one
  PhotoKit album titled after the event.
- Keep the choice **fixed for the membership**, persisted on `EventConfig`, default off.
- Make album placement **best-effort** (cosmetic; a missed photo is never a data-loss bug).
- Keep all decision logic in a **tested `commonMain`** coordinator; keep only `PHAssetCollection` calls
  in the untested iOS layer.
- No backend, engine, ledger, or data-migration changes.

**Non-Goals:**
- Runtime toggling of the album option after join (change = leave & rejoin).
- A curated subset — the album mirrors the whole synced set (uploads are already cutoff-filtered).
- Renaming the album when the event name changes (server-side rename is not possible; user renames are
  respected).
- Deleting the album on leave (it is the user's data).

## Decisions

### D1: Opt-in checkbox on the single join surface, persisted on `EventConfig`

A `saveToAlbum: Boolean = false` checkbox in the reserved slot on `JoiningEventScreen`, held in Compose
local state like the cutoff/direction, passed out via the confirm intent, and persisted on
`EventConfig` (whole-object → no port changes, no migration; a legacy config decodes to `false`). It is
the joiner's **local** choice, so it is **not** in the canonical QR (only a dev/test override rides the
deeplink — D8). Offered in **all three** directions: DownloadOnly → album of received photos,
UploadOnly → album of contributed photos, Both → the union. Because create-event auto-routes into the
same gate, the one checkbox covers create-join and scan-join.

### D2: Event name made non-nullable; the album is titled after it

The album needs a name, so `EventDetails.Found.name`, `EventConfig.name`, and `CreateOutcome.name`
become non-null (`String`). A `200 /events/:id` response lacking a name is treated as a **transient
`Failed`** (retryable), not a `Found` with a null name — the details gate never yields a nameless
loaded event. This is **client-only**: the backend already enforces name-required on create
(`event-creation`: trim, reject empty/whitespace/over-long), so a nameless event cannot exist; the
prior nullability was purely defensive. No fallback-to-eventId, no album-title rename path.

### D3: The app is the sole album creator — eager, on the permission grant

Album creation is done **only** by the app, **eagerly** when photo permission transitions to `GRANTED`
(or at join if already granted). This reuses the existing `SnapSyncRoot.enableBackgroundUploadOnGrant`
grant collector (a sibling "ensure album on grant") and the `provisionEvent` `GRANTED` check.

The key property: **sync itself requires the same `.readWrite` permission**, so "create on grant"
guarantees the album exists before the first synced photo can be produced. Therefore **both** the app
and the extension only ever **add** to an existing album — they never create — which **eliminates the
cross-process duplicate-album race** that a lazy "create on first photo" would introduce (both
processes could be the first photo). Accepted trade-off: a checked-but-never-syncing event yields an
empty album.

_Alternative considered:_ lazy create on first photo. Rejected — with uploads in the extension and
downloads in the app, two processes race to be the creator.

### D4: Album identity remembered per event in a leave-surviving store; reuse on rejoin

On creation the app stores the album's `PHAssetCollection` `localIdentifier` in a small
`eventId → albumLocalId` map. This map lives in a **separate shared store** (App-Group / shared
Keychain, reachable by both the app and the extension) that **`LeaveEvent.leave()` does NOT clear**
(unlike `EventConfig`). On **re-join** the app resolves the stored id: if the album still exists, it is
**reused** (history preserved); if it no longer resolves (the user deleted it), a fresh album is
created and the map overwritten. Within a membership a user-deleted album is **not** recreated — it is
recreated only on a re-join with the box checked again. Identifying by stored `localIdentifier` (not by
title) is robust to the user renaming the album and to two events sharing a name.

### D5: Downloaded photos — atomic add inside the importer's existing commit

`IosPhotoLibraryImporter` already creates each foreign asset inside one
`PHPhotoLibrary.performChanges { PHAssetCreationRequest… }`. When the membership opted into an album,
the importer, **in the same commit**, grabs the album's `PHAssetCollectionChangeRequest` and adds the
new asset's placeholder — so a received photo is atomically already-in-the-album, never briefly loose.
The importer takes an injected `albumId: () -> String?` lookup (the map read), mirroring the existing
injected `recordCreatedLocalId`; `DownloadController` stays pure and album-unaware.

### D6: Uploaded photos — add at cycle completion, in whichever process ran it

The user's own photos already exist in the library; there is nothing to create, so the add is a
separate step at `UploadCycle` completion. It runs in the process that ran the cycle — the **extension**
on iOS ≥26.1, the **app** on 18–26.0. The completed job carries **only its URL-derived key** by design
(`PlatformUploadJob.key` — `resource` is nil for succeeded jobs), so the asset must be re-fetched.

### D7: Raw `localIdentifier` recovery by reversal (best-effort), no new storage

The ledger key holds the **normalized** assetId (`normalizeAssetId` = `/`→`_`, one-way). To
`PHAsset.fetchAssetsWithLocalIdentifiers` we need the raw `localIdentifier`. We recover it by reversing
`_`→`/`. This is **exact** for real ids: every `localIdentifier` is `{UUID}/L0/NNN` (UUID = hex+hyphen,
no underscores), so the only underscores present are the ones that were slashes. The app **already
relies on this invariant** (the entire upload-key round-trip via `assetIdFromUploadKey` would break if
ids contained `_`), so reversal widens no trust surface. The fetch is **best-effort**: an empty result
(deleted asset, or a hypothetical exotic id) simply skips the add — acceptable for a cosmetic feature.

_Alternatives considered:_ (a) store the raw id in the ledger row — rejected: the row may be pruned at
completion (the reason `reconstruct()` derives from the key, not the row), so it degrades to
best-effort anyway, **and** it leaks a PhotoKit concept into the platform-free `:domain:engine` schema.
(b) a persistent `assetId → rawLocalId` side-map written at discovery — rejected as over-engineering: a
per-asset write growing with the library, for a cosmetic album. Reversal, the ledger, and the side-map
all converge on identical *behavior* (skip the deleted/exotic case); reversal needs no new state.

### D8: Dev/test `saveToAlbum` override on `EventLinkPayload`

The strict deeplink decoder rejects unknown keys, so the override is a **declared** optional field on
`EventLinkPayload` (like `minPhotoDate` / `direction`), additive within `v=3`, absent by default,
consumed on the `autoJoin` path. `autoJoin` still defaults album-**off**; the override lets the
headless USB loop / world harness force album-on without WebDriverAgent taps.

### D9: The testable seam — `AlbumCoordinator` over `AlbumManager` + `AlbumMapStore`

A thin `commonMain` `AlbumCoordinator` (tested in `:test:world`) owns the decisions:
`ensureAlbum(eventId, name)` (app-only: resolve map → reuse-if-exists, else create + store) and
`place(eventId, rawLocalIds)` (both processes: resolve map → `addAssets`, else skip+log). It depends on
two seams — `AlbumManager` (iOS `PHAssetCollection` create/exists/add) and `AlbumMapStore` (the
shared leave-surviving map). `:test:world` fakes `AlbumManager` (records `createAlbum`/`addAssets`) and
uses an in-memory `AlbumMapStore`, so integration tests assert "the album received exactly these
assetIds" and reuse-on-rejoin without PhotoKit. **Asymmetry (intentional):** the coordinator owns
**creation** and **upload-add**; the **download-add** stays inline in the importer for same-commit
atomicity (D5) and only borrows the album-id lookup — each choice driven by commit-atomicity, not
uniformity.

## Risks / Trade-offs

- **Extension `PHAssetCollection` capability was unverified** → **de-risked by an on-device spike**
  (below). Verified the extension can create + add. Consequence: the extension-wired real-time
  upload-add is viable and **no app-side reconcile net is needed**.
- **Reversal relies on the "`localIdentifier`s contain no `_`" invariant** → not a new assumption; the
  existing key scheme already depends on it, and the fetch is best-effort so a violation just skips.
- **Empty album for a checked-but-never-syncing event** → accepted (D3); the price of race-free
  single-creator eager creation.
- **A user-deleted album is not recreated mid-membership** → intended (D4); recreation only on rejoin,
  so we never fight a deliberate deletion.
- **Album mirrors the whole synced set (can be large)** → intended; uploads are already cutoff-filtered,
  and the mental model is "all of event X on this device."
- **Two processes both add** → safe: creation is single-writer (app), adds are idempotent (adding an
  asset already in a collection is a PhotoKit no-op), and `AlbumMapStore` is a shared read for both.

## Spike (resolved the one structural unknown)

**Question:** can the background-upload **extension** process create and mutate a `PHAssetCollection`?
Nothing in the app did this, and if blocked, the ≥26.1 upload-add would have had to move app-side.

**Result — PASS.** A throwaway probe added to `UploadExtensionRoot.process()`, built via the ssh-mac
loop, dev-signed, and sideloaded to the SE2 (iOS 26.5), ran on a real OS-scheduled extension
invocation and logged to the extension's `debug.log`:

```
[process] [Info/AlbumSpike] spike: BEGIN authStatus=3 assetCount=1
[process] [Info/AlbumSpike] spike: SUCCESS committed collection=477EE58B-…/L0/040 assetAdded=true
```

`performChangesAndWait { creationRequestForAssetCollectionWithTitle("SnapSync Spike") + addAssets([…]) }`
returned `error == nil` (PhotoKit populates `error` on an entitlement/sandbox denial). So from inside
the extension, on device, both **create** and **add** succeed. The probe was reverted after the result
was recorded.

## Migration Plan

No migration. A persisted `EventConfig` predating this change decodes to `saveToAlbum = false` (field
default under `ignoreUnknownKeys = true`) — today's behavior. The `name` tightening is client-only and
the backend already guarantees a name. No backend deploy, no ledger reset, no album back-fill. Rollback
is removal of the field, the seams, and the placement hooks; the leave-surviving album map is inert
without the feature and can be dropped.

## Open Questions

_None outstanding — granularity (both up+down), default (unchecked), mutability (fixed at join),
naming (non-null name), album identity (stored localId + reuse-on-rejoin), creation timing
(app-sole-creator on grant), raw-id recovery (reversal), and the extension capability (spike-passed)
were all resolved before this proposal._
