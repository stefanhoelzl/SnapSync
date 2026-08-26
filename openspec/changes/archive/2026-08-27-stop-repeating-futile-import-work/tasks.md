## 1. Store: a terminal unimportable state

- [x] 1.1 Add the terminal state to `DownloadState`. **No DDL migration was needed** — `state` is a `TEXT`
      column behind an enum adapter, so widening the value set changes no schema.
- [x] 1.2 Exclude the new state from `importableAssets()`, `unconfirmedImports()`, `pendingDownloads()`
      and the prune's `selectPrunableAssets`, by the same predicate that already excludes `IMPORTED`.
- [x] 1.3 Add the settling write to `DownloadStore` — guarded like `confirmCreatedLocalId`, reporting
      whether it applied — and drop the row's resource rows as part of it.
- [x] 1.4 Keep the new state out of `suppressedLocalIds`: a terminally unimportable row carries no marker
      and must contribute nothing to the suppression set.
- [x] 1.5 Exclude it from the download counts' outstanding total, so the status surface can still reach
      completion (capability `sync-status`).
- [x] 1.6 Mirror all of the above in `InMemoryDownloadStore`, keeping `FakeHonestyTest` green.
- [x] 1.7 Extend the `LedgerStore`/`DownloadStore` contracts in `:test:world` so both the SQLDelight and
      fake implementations are held to the new behaviour.

## 2. Adjudication: one sweep, one call site

- [x] 2.1 Delete the `adjudicateUnconfirmed()` calls from `reconcile`, `importReady` and
      `onResourceStaged`.
- [x] 2.2 Expose the sweep as a single suspend entry point on `DownloadController` that runs the batched
      lookup and then a drain, so a cleared marker is imported in the same process.
- [x] 2.3 Call it exactly once from `snapSyncApp`'s startup path, ordered **after**
      `installPermissionSubscriptions()`, and from nowhere else.
- [x] 2.4 Update the batched-lookup and `importing` KDoc to describe a per-process recovery sweep;
      remove the "costs nothing in the ordinary case — no row carries a marker" claim, which inverts
      under a burst.
- [x] 2.5 Correct the `importing` KDoc's premise: delete the `⏰ Measure it` note and the claim that a
      transaction cannot outlive its process — measured false on 2026-08-09 — and restate the safety
      argument on the *present* branch, citing
      `changes/archive/2026-08-10-take-imports-off-the-download-lock`.

## 3. Give up on an import the library cannot perform

- [x] 3.1 Extend `ImportResult.Failed` (or add a sibling) so the importer reports whether any resource was
      ingested — the distinction between a content rejection and a request rejection.
- [x] 3.2 In `importOne`, settle the row terminally when the failure consumed the staged file(s) and no
      asset was created; leave the row importable when the request was rejected before ingest.
- [x] 3.3 Log the settlement at `Error` severity, naming the ref and the library's reported error, so it
      reaches Bugsink (capability `crash-reporting`).
- [x] 3.4 Release any staged bytes that remain for a terminally settled row, after the settling write.
- [x] 3.5 Handle the drain selecting a row whose staged file no longer exists (process death between
      ingest and the marker write): settle terminally rather than retry against a missing path.

## 4. Move rather than copy

- [x] 4.1 Set `shouldMoveFile = true` on the `PHAssetResourceCreationOptions` in
      `IosPhotoLibraryImporter`.
- [x] 4.2 Map the library's error to the ingested/not-ingested distinction task 3.1 introduces —
      `PHPhotosErrorInvalidResource` (content, file consumed) versus `PHPhotosErrorChangeNotSupported`
      (shape, nothing consumed) — and state in the KDoc that the mapping is measured, with its expiry.
- [x] 4.3 Stop assuming `releaseStagedBytes` has files to delete; it must remain best-effort and must
      still drop the resource rows when the files are already gone.

## 5. Tests

- [x] 5.1 `commonTest`: a burst of staged resources and drains performs **zero** presence lookups when no
      row was inherited (the regression this change exists to prevent).
- [x] 5.2 `commonTest`: the sweep runs exactly once per composition, no matter how many triggers enter the
      download arm. Covered INDIRECTLY, and worth saying so: triggers are proven not to sweep (5.1) and the
      composition has one call site (5.6). No test counts sweeps per composition directly.
- [x] 5.3 `commonTest`: an inherited unconfirmed row whose asset exists is settled and its bytes released;
      one whose asset is absent has its marker cleared **and is imported in the same pass**.
- [x] 5.4 `commonTest`: a content-rejection failure settles terminally, is not offered on any later
      trigger, and emits an `Error`-severity line; a shape-rejection failure stays importable.
- [x] 5.5 `commonTest`: a row whose staged file has vanished settles terminally rather than looping —
      covered at the controller, which is where the decision lives. The step that CLASSIFIES a vanished file
      (`PHPhotosErrorMissingResource` → consumed) is iOS-only adapter code and is untested off-device; task
      6.1 is what would exercise it.
- [x] 5.6 `:test:integration`: the ordering guarantee — no import runs in a process before that process's
      sweep completes — asserted directly, since nothing enforces it at compile time.
- [x] 5.7 `:test:integration`: under a partial grant, assert the sweep observes a populated selection
      snapshot — the row stays unconfirmed while the snapshot is null, and settles once the observer emits.
      Revert-proofed: deleting the wait in `snapSyncApp` turns it red.
- [x] 5.8 Confirm the existing `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`
      test still passes unchanged; this change does not touch that residual.

## 6. Verify on hardware

- [x] 6.1 Confirm the move semantics on the SE2, not only the simulator. **DONE 2026-08-26** — SE2
      (iPhone12,8), iOS 26.6, dev-signed rig build, full grant, 1,474-asset library: all five cases
      reproduced the simulator's outcomes exactly (`3302` consumed the file with no asset created; `3300`
      consumed nothing). Recorded in `design.md` and in the importer's KDoc.
- [x] 6.2 Reproduce a foreign-photo burst on device and confirm `debug.log` carries **no**
      `absent, but its import is in flight` lines. **DONE 2026-08-26** — SE2 / iOS 26.6 against a local
      filesystem backend seeded with a synthetic foreign contributor (40 assets, 51 MB): 79 staged-resource
      callbacks, 40 imports, staging and importing interleaved within the same millisecond, and **0**
      adjudication verdicts / **0** in-flight discards against the 1,164 / 1,149 baseline. Counts reached
      40/40 with 0 in flight.
- [ ] 6.3 Confirm an importing asset no longer holds its bytes twice — staged file plus library copy —
      between the commit and the client's release. MECHANISM confirmed on device (the probe's
      `move_success` case consumes the file; the 40-asset burst imported successfully with the move
      option); the WINDOW itself is unmeasured, because nothing on the host can sample the App-Group
      staging directory mid-burst and both old and new code end with the bytes gone. Needs a rig verb
      reporting that directory's byte total, polled through a burst.
      ⚠️ The original wording of this task ("peak staging storage is no longer double") was an
      OVERSTATEMENT, and is corrected in the proposal, design and spec: the doubling move removes is
      per-asset and lasts only that window. The staging backlog is unchanged.

## 7. Gates and records

- [x] 7.1 `./gradlew build` green — including `:test:architecture`, the shell gates and `detektAppShell`.
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` green for the iOS source sets.
- [x] 7.3 `./gradlew architectureDiagrams` and commit if anything moved; stale `architecture/` blocks the
      PR.
- [x] 7.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green after the specs are
      synced.
- [ ] 7.5 PR carries exactly one changelog label — `bug` (a photo that never arrives and unbounded
      background work are customer-visible), applied by `/ship`.
