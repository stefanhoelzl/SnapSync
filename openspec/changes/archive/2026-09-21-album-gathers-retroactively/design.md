## Context

The event album (capability `event-album`) is populated in two places today:

- **own photos** at **first enqueue**: `UploadCycle.placeFirstEnqueued` (`UploadCycle.kt:495`), before any job
  is created, in whichever process runs the cycle. Decision record:
  `changes/archive/2026-09-15-retire-uploaded-state`;
- **foreign photos** at **import**: added atomically inside the importer's own PhotoKit commit, using
  `AlbumCoordinator.albumIdFor`.

Both are forward-only. The spec made that a SHALL: *"photos already imported or already enqueued SHALL NOT
be retroactively gathered"*. `reconfigure-membership` repeats it in the helper text the member reads.

That leaves three gaps, all measured against the tree:

1. **Toggle-on**: a member who turns the album on finds nothing they had already shared or received in it.
2. **Carried-over photos.** The ledger is keyed by bare filename, is not scoped to an event, and survives
   leave (`sync-ledger`, "Event-independent key"). A photo uploaded during an earlier event that the new
   event's window admits is listed in the new event's manifest (`manifestRows()` is not state-scoped) but
   never re-enqueued, because its row is `COMPLETED`. So no pass ever places it, even with the album on
   from join.
3. **Import before the album exists.** The spec says the importer skips the add when no album id is
   available, and that *"a later placement covers it"*. Today no later placement exists for foreign photos.

The rule the user settled replaces forward-only: **the album mirrors the event as this device holds it**.
That means any photo in this event that the device holds, however it arrived.

Constraints that shape the design:

- `AlbumCoordinator` is constructed in **both** processes. The extension builds one in
  `UploadExtensionRoot` for enqueue-time placement.
- The reconfigure command is built with `awaitingOnCoreLane` (`SnapSyncApp.kt:1005`). Save waits for
  everything `ReconfigureEvent.reconfigure` does.
- Law *"A trigger flow never outlives its own run"* (`module-architecture`). A `flow/` class may not detach
  work behind any `Unit`-returning lambda. `Provision` is a flow, and the iOS shell runs it.
- The download store is **event-blind** by design: it is keyed by `(sourceDeviceId, sourceAssetId)`, with
  cross-event dedup. Imported rows are permanent. `suppressedLocalIds()` spans every event the device has
  ever joined.
- `IosAlbumManager.add` does one `fetchAssetsWithLocalIdentifiers` plus one `performChangesAndWait` for
  the whole list. `AlbumCoordinator.place` forwards the list unchunked.
- The event window is at most 30 days (`event-limits`). The membership policy clamps to `[startsAt, endsAt]`,
  so the own set is bounded by one window, not the camera roll.

## Goals / Non-Goals

**Goals:**

- Place every photo the device holds for the event, including photos from before the opt-in, and photos
  carried over from an earlier event that this event's window admits.
- Keep all gathering in the app process, detached from the command that triggered it.
- Add no durable state: no placed-keys record, no download-store migration, no ledger change.
- Keep the gather's decisions in tested `commonMain` code.

**Non-Goals:**

- Changing enqueue-time placement or `UploadCycle` in any way. Phase 5 of the upload rework rewrites
  `UploadCycle.enqueue`, and this change must not collide with it.
- Changing the atomic add at import.
- Removing photos from the album when they leave the event (a narrowing change, a deleted photo). The album
  only ever grows, as today.
- Gathering on every cold launch.

## Decisions

### D1. The own set is the device manifest projection

The own set is `manifestRows()` → `admittedAssetIds(rows, policy)` under the membership's **current**
policy, reversed with `denormalizeAssetId`. That is exactly the set `projectDeviceManifest` declares
(`DeviceManifest.kt:95-96`), and the same projection the status total N counts. So the album holds what
this device tells the event it contributes: no more, no less.

Its event-independence is **correct** here, not a leak. A carried-over `COMPLETED` row that the new
policy admits *is* contributed to the new event, because it is in the new event's manifest.

*Rejected:*

- **Rows whose `LedgerEntry.eventId` matches.** This misses carried-over rows, which is the gap this change
  closes. It also relies on a field with a `""` sentinel that phase 2 of the rework removes.
- **The union's own-device entries.** The union lists only **complete** assets, so placement would lag
  enqueue-time semantics, and the own half would pay a network read it does not need.

### D2. The foreign set is the event union joined to imported rows by ref

The foreign set is the union's assets whose `deviceId` is not this device's, resolved through a new read,
`DownloadStore.importedLocalIds(refs): Map<AssetRef, String>`. A union entry and a download row share the
key by construction: `DownloadController` builds `AssetRef(asset.deviceId, asset.assetId)` from the union
(`DownloadController.kt:155`).

The question the gather asks is *"is this asset in this event?"*, and the union answers that. The store
stays event-blind.

*Rejected:*

- **`suppressedLocalIds()`.** It is event-blind, so it would place an earlier event's photos in this event's
  album.
- **Stamping an `eventId` on `downloadAsset`.** That is a durable migration and a `download-store` rewrite.
  It would also be **wrong**: a ref listed by two events has one row, so the first write would win.

The read returns only `IMPORTED` rows. An **unconfirmed** row (non-terminal, carrying a marker) is left out:
its asset's existence is adjudicated at process start, and the next gather places it once it has settled.

It is implemented as one select of all `IMPORTED` rows that carry an identifier, filtered in Kotlin by the
asked refs. The table is small (the union sizes of the events this device has joined), and binding a list
of key pairs is awkward in SQLDelight. The port contract is stated by ref, so a later index-driven query
changes nothing for callers.

### D3. `AlbumGather` is a separate, app-only class in `feature/album`

The gather is a new class, not a method on `AlbumCoordinator`. The coordinator is constructed in the
extension too. Giving it ledger, union and download-store dependencies would force the extension to supply
ports it must never use for this. A class built only in `SnapSyncApp` makes "the extension never gathers"
true **by construction**. Nothing in the extension's graph can call it.

`AlbumGather` takes:

- the config source;
- `LedgerStore`;
- a `policyFor: suspend (EventConfig) -> SelectionPolicy` lambda, bound in `compose/` to the same
  `selectionPolicyForMembership` every other reader uses;
- `EventUnionSource` and `DownloadStore`;
- this device's id;
- an access predicate;
- the `AlbumCoordinator`.

It adds through `AlbumCoordinator.place`, reusing that method's skip-when-no-album and `runCatching`.

**It also owns its own launching** (settled during implementation). `start(trigger, eventId)` launches a
gather on the composition scope and tracks the job. `onAccessObserved(usable)` holds the grant-transition
rule (D4). `awaitStarted()` lets the harness and tests wait. The first cut put the launch, the job tracking
and the transition check in `AppCore`. That broke three `compose`-tier ceilings: `TooManyFunctions` (12 >
11), `CognitiveComplexMethod` on `installPermissionSubscriptions` (10 > 6), and `LargeClass` (`AppCore`
measured 403 > 400 by bisection, with `main` at about 397). Those ceilings only fall. Moving the three into
the feature was the better placement anyway: the transition rule is a decision, and it is now tested in
`AlbumGatherTest` rather than only through the world. The two pieces of album-only composition,
constructing `AlbumGather` and the album's permission subscription (ensure, then `onAccessObserved`), live
in `compose/AlbumGatherComposition.kt`, beside `AppCore`. This is the same top-level-function pattern as
`uploadCore(…)`. `AppCore` keeps one field and three one-line call sites. No ceiling was raised.

`gather(eventId)` does the following, in order:

1. Take the mutex.
2. Re-read the config. If it is absent, names another event, has opted out, or access is not usable,
   return.
3. Build the own set. This is a local read and cannot fail on the network.
4. Read the union. On failure, log it and continue with the own set only.
5. Resolve the foreign refs to local identifiers.
6. Add the combined raw identifiers in batches (D5).

The gather never calls `ensureAlbum`. Its triggers call `ensureAlbum` first, and a gather that also created
albums would race the grant subscription's creation into a **duplicate album**.

### D4. Triggers: opt-in acts plus the in-process grant transition, all in `compose/`

This is the user's decision. The gather starts on a provision, a reconfigure Save, and access becoming
usable while the app runs. It does not start on a cold launch that is already granted. Each trigger is
composition wiring. None of them lives in the shell or in a flow:

- **Provision.** Every provision route (interactive join, switch, retry, `autoJoin`, create routed into the
  join gate) reaches the shell through `JoinEvent`'s injected `provision` lambda (`SnapSyncApp.kt:580`). The
  composition wraps that lambda: run `ports.provision(cfg)`, then start the gather.

  `Provision` itself is **not** touched. Detaching a gather from inside it would break the trigger-flow law,
  even behind a `suspend` lambda. The law exists because the flow cannot see which of its lambdas were
  backed with a detached launch. `JoinEvent` is a feature, and the detached launch happens in the
  composition, which already does the same for reconfigure's `startDownloads`.

  Consequences: `architecture/flows/Provision.md` does not change, and the iOS shell does not change.
- **Reconfigure.** `ReconfigureEvent` gains a `gatherAlbum: suspend (EventConfig) -> Unit` effect, called
  unconditionally right after `ensure album`, under the same best-effort `step`. The composition backs it
  with a detached launch, the same shape as `startDownloads`. Save therefore returns once the config is
  saved and the album is ensured. Keeping the call in the use-case keeps its ordering after `ensureAlbum`
  covered by `ReconfigureEventTest`.
- **Grant.** This extends the **existing** album permission subscription. It does not add a second
  collector. (As built, that subscription moved out of `AppCore` into
  `AlbumGatherComposition.kt#launchAlbumGrantSubscription`; see D3.) The collector keeps calling
  `ensureAlbum` on every usable emission (unchanged). **After** that `ensureAlbum` returns, it hands the
  emission to `AlbumGather.onAccessObserved`, which remembers the previous usable-or-not value. It starts a
  gather only when this emission is usable, the previous one was not, and this is not the first
  emission:
  - skipping the first emission (the `StateFlow` replay) means an already-granted cold launch gathers
    nothing;
  - requiring a not-usable previous value means a `LIMITED`→`GRANTED` upgrade (usable→usable) does not
    re-gather.

  One collector is the point. A second collector on the same `StateFlow` would run its own `ensureAlbum`
  concurrently with the first. That is two creations for one album-less membership: exactly the
  duplicate-album race that D3 keeps the gather away from.

*Rejected:*

- **Every `ensureAlbum`, including cold launch.** This self-heals a failed union read, but it adds a union
  read and library writes to every launch, at an unmeasured cost.
- **Toggle-on only.** This leaves gap 2 (carried-over photos) open.
- **Reacting to the config `StateFlow`.** A config write precedes `ensureAlbum` in both provision and
  reconfigure, so the gather would find no album on a fresh join. Making it ensure the album itself races
  the grant subscription into a duplicate album.

### D5. Batching lives in the gather, in `commonMain`

The gather adds in batches of `GATHER_BATCH_SIZE`, a named constant with an initial value of **500**. Each
batch is one `place` call, so one fetch and one `performChangesAndWait`. `place` already swallows a failed
add, so a failed batch does not stop the rest.

*Rejected:*

- **Chunking in `IosAlbumManager.add`.** It is an untested iOS shell, so a size decision there cannot be
  asserted. It would also change the enqueue path, whose slices are already bounded by the enqueue batch.
- **No chunking.** That is one library transaction over up to a window's worth of photos, at an unknown
  cost.

**Measured** on 2026-09-21 (task 6.1): iOS 26 simulator, an iPhone 17 Pro, on a `macos-26` runner. The
library was seeded with 2,000 photos through the rig's `gallery/seed`. The real `IosAlbumManager.add` was
timed into a fresh album per size, then again with the same ids:

| batch | first add (2 runs) | repeat add, already placed |
|---|---|---|
| 100 | 94 / 67 ms | 25 / 13 ms |
| 500 | 318 / 320 ms | 37 / 29 ms |
| 2,000 | 1,408 / 1,249 ms | 87 / 125 ms |

The cost is **linear**, about 0.65 ms per asset, with no blow-up for one large transaction. So the batch size
does not change a gather's total cost. It only bounds how long one library change blocks its thread. At
**500** that is about 0.3 s on this host, so the constant stays 500. `placed` equalled the size after two
adds, so a repeat adds nothing twice. The repeat costs about a tenth of a first add, so a re-gather over an
unchanged event is cheap.

These are Mac-hosted simulator numbers, so a phone will be slower in absolute terms. The linear shape is the
finding that set the constant. The instrument was a throwaway rig verb (`album/timing`), never committed:
it creates one album per size and times two `add` calls.

### D6. One gather at a time, no placed-keys record

A `Mutex` in `AlbumGather` serializes runs. Each run re-reads the config under the lock, so a Save that
lands during a gather is honoured by the queued run, not lost.

Nothing records what was placed. `addAssets` on an asset already in the collection is a no-op (measured,
simulator, iOS 26.5; cited in `UploadCycle`'s album KDoc), so a repeated gather is correct at O(N).
`IosAlbumMapStore` stays `eventId → albumLocalId` only.

### D7. The helper text says what will be true

`ReconfigureScreen`'s album note changes from *"… Only photos synced from now on are added."* to
*"Photos are collected in an album named after the event, including the ones already synced."* The note
does not adapt to direction, and it should not claim "received" for a membership that only shares, so it
says "synced". The `StatusScreenTest` assertion follows it.

## Risks / Trade-offs

- **[A photo the member removed from the album comes back]** Today a removal sticks, because placement
  happens once, at first enqueue. Under this change the next opt-in act (any Save with the album on, a
  re-join, a grant) re-adds it. → Accepted as the consequence of "the album mirrors the event". It happens
  only on explicit acts, not continuously. It is recorded here so it reads as a decision, not a bug.
- **[The cost of one `performChangesAndWait` over a batch is unknown]** → Batching (D5) plus a simulator
  measurement task that sets the constant. The gather runs on the composition lane, never the UI lane,
  because `performChangesAndWait` blocks its thread.
- **[A failed union read is not retried on its own]** → The own half still lands. The foreign half waits
  for the next opt-in act. Placement has always been best-effort, and the user accepted this with the
  trigger choice.
- **[The limited-access alert]** It is armed by the library gaining content outside the selection, not by
  reads. Every asset the gather adds is already in the selection: own photos came from it, and imports
  join it at creation. So the gather should arm nothing.

  → **Measured, passed** (task 6.2; 2026-09-21; SE2 on **iOS 26.6.2**; a rig build carrying
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` as shipped):

  - **Setup:** the member granted `.limited` with 3 photos selected. Two cold launches were clean.
  - **Gather:** creating an album and adding the 3 selected photos (`IosAlbumManager.ensureCreated` +
    `add`, the gather's exact PhotoKit calls) raised **no alert**. That covered the same process after a
    forced fetch, a cold relaunch, and the home screen after SIGKILL. The adds succeeded (`placed: 3`),
    confirming on device that album creation and adding work under a partial grant.
  - **Positive control:** it **failed**. The member took one Camera photo outside the selection, then ran
    4 launch → 3 fetches → SIGKILL cycles, 11 fetches in all, with a home-screen screenshot after each
    kill. There was **no alert**, and the member confirmed none appeared at any point.

  **Why it counts as a pass.** The prompt is iOS nudging the member to add new photos to a partial
  selection. It is not a guard on reads: under `.limited` the app reads only the selection, and a gather
  adds nothing outside it. With `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true`, the documented
  suppression, nothing raised the prompt on iOS 26.6.2, the gather's calls included. So the gather adds
  no prompt the member would otherwise see.

  The same control does contradict the 2026-08-06 observation on iOS 26.5.2 (`correct-limited-access-read-
  premise`, P0c: one camera photo raised exactly one alert despite the key). Both results are n=1, on one
  device. That bears on CLAUDE.md's ① ("every photo the member takes costs one system prompt"), which is
  outside this change and is reported rather than edited here.
- **[Concurrent placement]** On iOS 18–26.0 the app runs the cycle itself, so a gather and a cycle's
  `place` can overlap. → Both add, and adding is idempotent. They share no state.
- **[Carried-over photos from a very old event]** They are gathered only if the current policy admits them,
  and that policy is bounded to this event's window. So nothing outside `[startsAt, endsAt]` is placed.

## Migration Plan

- No data migration. The new download-store read uses existing columns and the primary key.
- Existing memberships: the first opt-in act after the update gathers what they already hold. A
  membership with the album off is unaffected.
- Rollback: the gather writes no durable state. Backing it out is a build that stops starting it. Photos it
  placed stay in albums the member can edit in Photos.

## Open Questions

- ~~**The batch size.**~~ **Resolved:** measured linear; it stays 500 (D5).
- ~~**Whether the removed-photo-returns trade-off is acceptable.**~~ **Resolved, accepted by the user
  (2026-09-21).** A photo removed from the album, but kept in the library, returns on the next gather,
  including a reconfigure Save of an unrelated setting while the album is on. The alternatives were
  rejected: gathering on Save only when the album is turned on, and a placed-photos record (which the
  handoff had already settled against).
