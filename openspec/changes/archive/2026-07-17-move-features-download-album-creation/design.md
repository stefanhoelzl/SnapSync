# Design — move-features-download-album-creation

## Context

Migration step 6 (PLAN.md "features II"). Step 5 populated `feature/{upload,membership,status,trust}`
and armed the feature-blindness gate; it deliberately left `DownloadStatusSource` in `:domain:status`
(its D6: seating it in `feature/status` would have forced a sibling reference when the download
feature moved here) and re-documented the ext-safe `api(":capability:album")`/`api(":domain:gallery")`
edges as dying at this step. Executed as whole-file `git mv`s; bodies byte-identical, `package`/
`import` lines only.

## Goals / Non-Goals

- Goal: `feature/download`, `feature/album`, `feature/creation` populated; the last illegal graph
  edge (`presentation→event-creation-ui`) dead; ten emptied modules deleted; the ext-safe interim
  edges dead; zero behavior change.
- Non-goals: flows/compose (7–8), UI re-homing (9), fake collapse (10), any body edit, fixing
  placement claims other steps falsified (accounted in the proposal instead).

## Decisions

### D1 — The deletion set is "verified sourceless", not PLAN's full candidate list
PLAN names `capability/*` wholesale plus `domain/gallery|status|permission|download-store|logging`.
Verified per module: ten are sourceless after the moves and die; `:capability:upload` (the
receivers file), `:capability:attest` + `:domain:gallery` + `:domain:download-store` (honest
doubles + stay-behind tests), `:capability:push` (registration/notify), `:domain:engine`
(contract tests) still hold content PLAN assigns to steps 7–10 and stay. `:domain:keychain` is
excluded by the corrected PLAN parenthetical (ProtectedData skeleton, step 12).

### D2 — `DownloadPushReceiver` moves; `UploadPushReceiver` stayed (the asymmetry, argued)
Step 5 parked `UploadPushReceiver` in `:capability:upload` as "flow material, step 8". Its file
also holds `FanOutPushReceiver` — the cross-arm fan-out that references *both* arms' receivers and
therefore has no lawful seat in any feature (it is compose/flow material by construction), and
move-fidelity forbids splitting the file. `DownloadPushReceiver.kt` holds only the download
receiver, which references `ports.PushReceiver`, its own feature's controller, and kermit — a
lawful `feature/download` file under the armed gate. Its active-event guard is a rule ("a push for
a non-active event is a no-op"), and rules live in features; step 8 will pull the *trigger* wiring
into `flow/` and leave the rule behind. Moving it is what makes `:capability:download` deletable —
the point of this step — where symmetry with upload would have kept a whole module alive for one
30-line class.

### D3 — Album seams to `ports/`, coordinator + migration decision to `feature/album`
`AlbumManager`/`AlbumMapStore` are the PhotoKit / shared-store I/O boundary — port interfaces by
the law's definition, and PLAN 3a already listed "album seams" under `ports/` (they were only
parked pending this step's move). `AlbumSeams.kt` contains exactly the two interfaces (no impl,
no feature logic), so the whole-file move is not zone-mixed. `AlbumCoordinator` (resolve-or-create,
dispatch-or-skip rules) and `albumMapSource` (the pure Keychain→App-Group migration decision over
a `ports.KeychainRead`) are feature rules → `feature/album`. The ext-safe adapters
(`IosAlbumManager`, `IosAlbumMapStore`) keep their legacy `app.snapsync.album` package (step-4 D2
posture) and gain explicit imports — before the move they saw the seams by same-package
visibility, so no import line existed to rewrite.

### D4 — `ResourceEnumerator` seats in `feature/upload` (interim until compose/, step 7)
It is the platform-free composition `PhotoLibrary = resourcesFrom ∘ RawAssetSource` — decision-free
glue whose target home is `compose/` ("holds the shared composition"), which does not exist until
step 7. It cannot stay put (the ext-safe edge it anchors must die here), cannot go to `model/`
(references `ports/` — the armed model-purity gate), cannot go to `ports/` (interfaces-only).
Among features, upload's discovery walk is its primary consumer (`IosDiscovery` wraps it; the
world's enumerator feeds the same cycle), and no other feature references the class (feature/status
consumes the `PhotoLibrary` *port*, so no sibling reference arises — verified by the armed gate).
An `:adapter:generic` seat was rejected: the class names no technology, and "adapters hold
implementations *named for the technology*" would be violated in the other direction. Step 7
relocates it into `compose/` as an intra-`:domain` move.

### D5 — `DeviceManifestProducer` seats in `feature/membership` (one-writer placement)
The manifest object under `events/<id>/devices/<deviceId>` is durable state behind the `Enrollment`
port, and `feature/membership` already writes it (`ManifestDeviceEnroller`'s register-empty
manifest; the producer's per-cycle manifest is the same object, last-write-wins by design). Seating
the producer in `feature/upload` would create a second writer *feature* for that durable port —
exactly what "exactly one writer feature per durable port" forbids. In membership, one feature owns
the write path (enroll = the empty registration, produce = the real manifest), and the producer's
own store (`DeviceManifestStore`: accumulator + last-uploaded marker) keeps its single writer too.
The roots invoke `produce()` per cycle — coordination from outside, which features stay blind to.
No `feature/upload` file references the producer (verified), so no sibling edge arises.

### D6 — Test placement: subjects where reachable, fixtures pin the rest
`:domain` has zero project dependencies, so a test can move with its subject only if it needs
nothing but model/ports and in-file fakes. Moved in: `QueuedPhotoDownloadJobsTest`,
`AlbumCoordinatorTest`, `AlbumMapMigrationTest`, `CreateEventTest`, `DeviceManifestProducerTest`
(all fake via in-file doubles). Left behind — and re-homed, because their old modules die:
- `DownloadControllerTest`, `DownloadPushReceiverTest`, `StoreDownloadStatusSourceTest` construct
  `InMemoryDownloadStore` → `:domain:download-store` `commonTest` (the fake's own module, which
  survives to step 10). Packages stay `app.snapsync.download`; only subject imports were added.
- `LedgerBackedSyncStatusSourceTest`, `OwnDeviceGalleryStatusSourceTest` construct the gallery
  fakes → `:domain:gallery` `commonTest` (same shape, step-5 D7's stay-behinds following the
  fakes). Packages stay `app.snapsync.status`.
All re-homed tests keep byte-identical bodies; they follow the fakes to `:adapter:fake` at step 10.

### D7 — The presentation edge dies by re-homing, not by API change
`:domain:presentation` consumed `MutableCreationStatusSource`/`NoOpEventCreator`/the seams from
`:capability:event-creation-ui`; all of `CreationStatus.kt` and `CreateEvent.kt` move whole-file
into `feature/creation`, so presentation reaches identical types through its existing
`api(":domain")` and the `api(":capability:event-creation-ui")` line is deleted. Presentation
observing feature read-models directly is the lawful read path ("reads do NOT cross flow");
the command-bundle discipline for `EventCreator` arrives with flow/ (step 8) and presentation
re-homing (step 9).

### D8 — Gate evidence is a planted red, not an assertion of coverage
The feature-blindness gate derives its scope from the directory listing, so the new features are
in scope by construction — which is precisely the claim that must not be taken on faith. Verified
non-vacuously: a scratch `feature/album` reference to `feature/download.DownloadProgress` turned
the gate red naming both packages, with zero gate edits; removed after. (The probe-removal
`git checkout` also reverted the file to its staged pre-edit content — caught by re-inspection and
re-applied; noted for the reviewer since it is exactly the class of silent loss a byte-review
catches.)

## Risks / Trade-offs

- [`DeviceManifestProducer` in membership surprises a reader who expects it beside the cycle] →
  the one-writer law argument is recorded here and in the CLAUDE.md row; the alternative (upload)
  is a law violation, not a style choice.
- [Import-sweep breadth (~15 consumer files)] → compiler-verified on JVM + iOS metadata; string
  literals untouched (`RuntimeIdentityTest` pins them regardless); per-symbol import rewrites,
  never blanket package sed.
- [Re-homed tests now live in modules that are not their subjects' homes] → deliberate and
  temporary (step 10 collapses fakes and tests together); the alternative — keeping two otherwise
  sourceless modules alive as test shells — would have left the module-set law two higher for no
  coverage gain.

## Migration Plan

Single working-tree change (branch `arch`, RUN.md model: implementer never commits). Rollback =
revert the diff; no durable state, schema, or identity string moves.
