## Why

Turning the event album on today mirrors only from that moment onward: a member who opts in after sharing
for a day finds none of what they already shared in it. The same forward-only rule leaves a quieter gap
even with the album on from join. A photo this device uploaded during an earlier event, and that the new
event's window admits, is listed in the new event's manifest but never enqueued again, so no pass ever places
it. The album is meant to be the one on-device statement of what belongs to the event. It should hold what
belongs to the event, whenever the member opted in.

## What Changes

- **The album mirrors the event as this device holds it**, not what the membership moved since opting in:
  any photo in this event that this device holds, however it arrived. This replaces the forward-only rule.
- **A gather** places what the device already holds, in two halves:
  - **own photos**: the device's contribution to this event, which is its manifest projection (the ledger's
    present rows, admitted by the membership's current policy);
  - **foreign photos**: the event union's foreign assets that this device has imported, resolved to local
    identifiers through the download store's import handle.
- **The gather runs on an opt-in act**: a provision (join, re-join, switch), a reconfigure Save with the
  album on, and photo access being granted while the app is running. It does **not** run on a cold launch
  that is already granted.
- **It runs in the app, detached.** Save and join never wait for it, and the extension never runs it.
- **It adds in bounded batches**, not one library transaction for the whole set.
- **It is best-effort**, like all placement: a failed union read, a missing asset or a failed add is
  logged, and the rest of the gather still runs.
- **The reconfigure surface's album helper text is rewritten.** It currently promises that only photos
  synced from now on are added, which becomes false.
- **The download store gains a read**: the local identifiers of imported assets, looked up by source ref.
  There is **no schema change**: every column it needs already exists.

Unchanged: placement of own photos at first enqueue in the upload cycle; the atomic add at import; the
album identity map; the rule that the app is the only process that creates the album.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-album`: the forward-only toggle is replaced by "the album mirrors the event as this device holds
  it", and a new requirement adds the gather (what it places, when it runs, where, how it batches). The
  join-surface requirement stops pointing at "forward-only terms".
- `reconfigure-membership`: the album helper text states that turning the album on gathers what the device
  already holds. On Save, the re-driven effects include the gather when the album is on.
- `download-store`: a new read returns the local identifiers of **imported** foreign assets, by source ref.

## Impact

- `:domain` `feature/album`: a new app-only `AlbumGather`. `AlbumCoordinator` is unchanged, and so is its
  construction in the extension.
- `:domain` `feature/membership`: `ReconfigureEvent` gains one injected gather effect.
- `:domain` `flow/Provision`: gains one fire-and-forget gather effect, which regenerates
  `architecture/flows/Provision.md`.
- `:domain` `compose/SnapSyncApp.kt`: wiring for both triggers, plus the grant-transition trigger on the
  permission subscription.
- `:domain` `ports/DownloadStore` + `:adapter:generic:app` (`DownloadStore.sq`, `SqlDelightDownloadStore`)
  + `:adapter:generic:fake` (`InMemoryDownloadStore`) + `:test:world` (`DownloadStoreContract`).
- `:ui:screens`: the `ReconfigureScreen` album note and its `StatusScreenTest` assertion.
- **Not touched**: `UploadCycle`, the ledger schema, the discovery walk, the upload lifecycle, and the
  upload extension.
- **Cost**: each gather does one union read (the download reconcile already does the same read), one local
  ledger read, and chunked library writes. N is bounded by the event window, at most 30 days of photos.
  The batch size is unmeasured until the simulator measurement in tasks.
- **Rollback**: the gather writes no durable state. At worst an album holds photos the member can remove
  in Photos, and backing it out is a build that stops calling it.
