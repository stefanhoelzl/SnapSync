## 1. Download store: imported local identifiers by ref

- [x] 1.1 Add `suspend fun importedLocalIds(refs: Collection<AssetRef>): Map<AssetRef, String>` to `DownloadStore` (`domain/ports/.../DownloadStore.kt`), NOT to `SuppressionSource`, with KDoc: IMPORTED only, event-blind, keyed by ref (design D2)
- [x] 1.2 Add the `selectImportedLocalIds` query to `DownloadStore.sq` (`state = 'IMPORTED' AND createdLocalId IS NOT NULL`) and implement the read in `SqlDelightDownloadStore` by filtering on the asked refs; confirm no schema/migration file changes
- [x] 1.3 Implement it in `InMemoryDownloadStore` (`:adapter:generic:fake`), keeping `FakeHonestyTest` green
- [x] 1.4 Add contract cases to `DownloadStoreContract` (`:test:world`), one per `download-store` delta scenario: imported answers its id; pending, unconfirmed-with-marker, unimportable and unknown refs are absent; only asked refs are answered

## 2. The gather feature

- [x] 2.1 Create `AlbumGather` in `domain/feature/.../album/` per design D3/D6: deps (config source, `LedgerStore`, `policyFor`, `EventUnionSource`, `DownloadStore`, own device id, access predicate, `AlbumCoordinator`), a `Mutex`, and `gather(eventId)` that re-reads config under the lock and returns on absent / other event / opted out / no access
- [x] 2.2 Build the own set: `manifestRows()` → `admittedAssetIds(rows, policyFor(cfg))` → `denormalizeAssetId`
- [x] 2.3 Build the foreign set: union read (a failure is logged and skips only this half) → refs whose `deviceId` is not the own id → `importedLocalIds`
- [x] 2.4 Add the combined ids through `AlbumCoordinator.place` in batches of a named `GATHER_BATCH_SIZE` (initially 500); `AlbumGather` never calls `ensureAlbum`
- [x] 2.5 Write `AlbumGatherTest` (commonTest, with fakes) covering each `event-album` gather scenario that is decided inside the feature: carried-over row gathered; another event's import not gathered; policy-excluded not gathered; union failure still gathers own; batches are at most the size and a failing batch does not stop the rest; opted-out, other-event and no-access gather nothing; two serialized runs each re-read the config

## 3. Triggers in the composition

- [x] 3.1 `ReconfigureEvent`: add a `gatherAlbum: suspend (EventConfig) -> Unit` effect, called unconditionally right after `ensure album` under `step`; update its KDoc and extend `ReconfigureEventTest` to assert the gather runs after the ensure and that a throwing gather does not abort the remaining steps
- [x] 3.2 In `SnapSyncApp.kt`: construct `AlbumGather` (app composition only) with `policyFor = ::selectionPolicyForMembership`; add a private detached starter on the app scope and composition lane (never the UI lane)
- [x] 3.3 Back `ReconfigureEvent.gatherAlbum` with the detached starter, so the awaited `tap.reconfigure` returns without waiting for the gather
- [x] 3.4 Wrap `JoinEvent`'s `provision` lambda: `ports.provision(cfg)`, then start the gather detached. Leave `flow/Provision` and the iOS shell untouched (design D4)
- [x] 3.5 Extend the existing permission subscription (a single collector): remember the previous usable value, and after its own `ensureAlbum`, start a gather only on a not-usable→usable change that is not the first emission
  — as built: the rule is `AlbumGather.onAccessObserved`, and the album subscription moved to `compose/AlbumGatherComposition.kt` so `AppCore` stays within the compose tier's ceilings (design D3)
- [x] 3.6 Confirm that `UploadExtensionRoot` and `UploadCore` do not reference `AlbumGather`, and that `UploadCycle.kt` has no diff

## 4. World and integration coverage

- [x] 4.1 `AlbumWorldTest`: turning the album on through the real reconfigure command gathers already-enqueued own photos and already-imported foreign photos into the fake album
- [x] 4.2 `AlbumWorldTest`: a join with the album on, over a ledger carrying a `COMPLETED` row the new policy admits, places that photo although no job is created
- [x] 4.3 `AlbumWorldTest`: an import recorded under an earlier event, absent from the new union, is not placed
- [x] 4.4 `AlbumWorldTest`: an already-granted start places nothing through the grant path, and a not-determined→granted change while running does gather
- [x] 4.5 Assert the triggering command returns before a gather blocked on its union read finishes, for both reconfigure and join

## 5. Reconfigure surface text

- [x] 5.1 Change the album note in `ReconfigureScreen.kt` to "Photos are collected in an album named after the event, including the ones already synced.", and update the forward-only code comment above it
- [x] 5.2 Rename and update the `StatusScreenTest` case: assert the new text, and that "Only photos synced from now on" is absent
- [x] 5.3 Search `ui/`, `site/`, `metadata/` and `screenshots`' source copy for any other forward-only album wording, and fix it or record that there is none
  — none: every "only photos … from here on" hit is capture-cutoff copy; `site/` and `metadata/` mention the album only as a keyword and as the iCloud Shared Album contrast

## 6. Measure before shipping

- [x] 6.1 On a simulator (`ios-simulator` skill), seed about 2,000 photos and time `IosAlbumManager.add` at 100, 500 and 2,000 ids; set `GATHER_BATCH_SIZE` from the result and record the numbers in `design.md` D5
  — linear, about 0.65 ms/asset (500 ≈ 0.32 s); `GATHER_BATCH_SIZE` stays 500; numbers in D5
- [x] 6.2 On device under a `.limited` grant, run one gather and confirm it surfaces no limited-access alert; record the outcome in `design.md` Risks
  — **passed** (2026-09-21, iOS 26.6.2): the gather's PhotoKit calls raised no alert under `.limited`; with the shipped suppression key no automatic prompt appeared on any path, the Camera control included; recorded in design Risks

## 7. Gates

- [x] 7.1 `./gradlew build` green (including the detekt tiers and `:test:architecture`)
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 7.3 `./gradlew architectureDiagrams`; commit any regenerated `architecture/` files (`ports.md`/`features.md` are expected to change; `flows/Provision.md` is not)
  — done: `features.md` and `ports.md` changed; no flow diagram changed
- [x] 7.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate album-gathers-retroactively --strict` green
- [x] 7.5 At sync: update the `event-album` `## Purpose` to mention the gather and cite this change's decision record

## Archive gates (2026-09-21)

- **Placeholder Purpose:** none in the tree.
- **Dead types:** the diff removes no type declaration.
- **Delta completeness**, per touched module:
  - `:domain:feature` (`AlbumGather`, `ReconfigureEvent`) → `event-album` (the gather), `reconfigure-membership` (the Save effect)
  - `:domain:ports`, `:adapter:generic:app` (`importedLocalIds`, its SQL query) → `download-store`
  - `:adapter:generic:fake` (`InMemoryDownloadStore`, `AlbumGatherTest`) → `download-store`, `event-album`
  - `:domain:compose` (the triggers, `AlbumGatherComposition.kt`) → `event-album`, `reconfigure-membership`
  - `:ui:screens` (the album note) → `reconfigure-membership`
  - `:test:world`: `DownloadStoreContract` → `download-store`. The `FakeAlbumManager.holdAdds` lever needs **no delta**: it lives in a `:test:world` class (the rigging-placement rule is satisfied), and `harness-world-model`'s *Failure levers* states what the world SHALL expose and forbids nothing additional.
  - `:test:integration`: tests only, asserting the `event-album` delta; `testing-architecture`'s placement rules are unchanged
  - `architecture/`: regenerated output; `architecture-diagrams` is unchanged
