# Tasks

Ordered so the store and the fakes are honest before anything depends on them, and so the regression
test can fail for the right reason before the fix lands.

## 1. Make the crash window reachable in tests

- [x] 1.0 **Unplanned prerequisite, discovered here.** `recordCreatedLocalId` existed only on the
      concrete `SqlDelightDownloadStore`, so the unconfirmed row was unreachable through the port — which
      blocked both 1.1 (the world composes via ports) and 2.3 (the contract only sees `DownloadStore`).
      Added `recordCreatedLocalId` **and** its mirror `clearCreatedLocalId` to the port, non-suspending
      (the platform's change block cannot suspend); implemented in `InMemoryDownloadStore`, `override`
      on `SqlDelightDownloadStore`, new `clearCreatedLocalId` query in `DownloadStore.sq`.
      `RecordingDownloadStore` inherits both by delegation. **The `download-store` delta was updated** —
      this is a contract change, not a mechanical one.
- [x] 1.1 Give `:test:world`'s `FakePhotoLibraryImporter` the same two-phase seam as the real adapter:
      a `recordCreatedLocalId`-shaped callback invoked before it returns, so "marker written,
      confirmation never arrived" is a state the world can reach. (It lives in `:test:world`, outside
      the honesty gate, so the lever belongs there — not in `:adapter:generic:fake`.) Wired in `World`
      to the download store, mirroring `SnapSyncRoot`.
- [x] 1.2 Add the levers — three, not two, because an import can end badly in three distinct ways:
      `failNextImport` (fails **before** creating), `failNextImportAfterCreating` (marker written, asset
      created, commit reports failure → the mirror clears the marker), and `abandonNextImport` (marker
      written, asset created, **never confirmed** → `TimedOut`, and deliberately no clear). Exposed on
      `World` as levers.
- [x] 1.3 `InterruptedImportIntegrationTest` written and **failing for the right reasons** — verified
      from the assertion messages, not just the red:
      · *duplicate* — `expected:<[(DEV-F, FQ)]> but was:<[(DEV-F, FQ), (DEV-F, FQ)]>`
      · *the reported harm* — "the first copy stays suppressed and creates no upload job"
      · *the compounding defect* — "the marker survives a leave"
      All preconditions pass first (asset created · marker recorded · import unconfirmed), so the world
      reaches the real state rather than failing early.
      **Needed one extra world change:** the fake importer minted a deterministic id per ref, so a repeat
      import reused the first one's handle and the orphaning was unreproducible. It now mints a fresh id
      per attempt, as PhotoKit does, keeping the bare form for the first so existing expectations read
      unchanged.

## 2. Store: expose the unconfirmed rows and the staged paths

- [x] 2.1 `DownloadStore.sq` — queries only, **no schema change**: select unconfirmed rows
      (`state != 'IMPORTED' AND createdLocalId IS NOT NULL`); staged paths for an asset, for all
      confirmed assets, and for all rows about to be dropped; delete an asset's resource rows.
- [x] 2.2 `deleteNonTerminalAssets` — add `AND createdLocalId IS NULL`.
- [x] 2.3 Mirror all of it in `InMemoryDownloadStore`, and extend `DownloadStoreContract` (runs against
      both impls): an unconfirmed row is in `suppressedLocalIds()`, is not offered as ordinary import
      work, and **survives `pruneNonTerminal()`**.
      Verified running against **both** impls: `InMemoryDownloadStoreTest` and
      `SqlDelightDownloadStoreTest`, 9 tests each, 0 failures. (`importableAssets` now excludes
      marker-carrying rows, which leave via the new `unconfirmedImports()`; `UnconfirmedImport`,
      `stagedPathsOfImportedAssets`, `stagedPathsOfPrunableAssets` and `dropResources` added to the
      port for phases 4 and 6.)

## 3. The presence seam

- [x] 3.1 `model/` — the three-valued verdict (present · absent · unknown).
- [x] 3.2 `ports/` — `ImportedAssetPresence`, batched: ids in, per-id verdicts out. Named for the need.
- [x] 3.3 `:adapter:ios:app-only` — the full-access implementation, owning its `Dispatchers.Default`
      hop (the call blocks its thread; `IosDiscovery` carries the forcing proof).
- [x] 3.4 `:adapter:generic:fake` — the honest in-memory implementation.
- [x] 3.5 `compose/` — the grant-aware binding, mirroring `PermissionAwareCandidateSource`: full access
      queries the library; partial access answers from the selection snapshot and never reports absent;
      no usable grant answers unknown. The download feature gains no permission knowledge.
- [x] 3.6 `:test:world` — presence backed by the world gallery.

## 4. The guard

- [x] 4.1 `DownloadController` — two phases: read unconfirmed rows and ask the port **outside** the
      mutex; apply verdicts and run the drain **under** it. Called from **all three** drain entry
      points (`reconcile`, `onResourceStaged`, `importReady`), always before the lock.
- [x] 4.2 Verdict handling: present → record the import against the existing marker; absent → clear the
      marker, then import; unknown → skip.
- [x] 4.3 One log line per non-trivial outcome; silent when no row carries a marker.
- [x] 4.4 `DownloadControllerTest` — the three outcomes.
      `a_failed_import_stays_importable_for_retry` still passes. Existing construction sites in
      `DownloadControllerTest`/`DownloadPushReceiverTest` updated for the new required port.
      **1296 tests executed across the four affected modules, 0 failures** (force-rerun — a
      cached `BUILD SUCCESSFUL` proves nothing).

## 5. The failure undo

- [x] 5.1 `IosPhotoLibraryImporter` — on a completion reporting failure, clear the marker it wrote.
- [x] 5.2 Do **not** clear on `TimedOut`. Assert it: that transaction may still commit, and clearing is
      what orphans the created asset. Also documented **why the undo still fires after a timeout**:
      `performChanges`' completion is an ObjC block untied to the coroutine, so a late failure
      still clears while a late success keeps its marker for the guard — an abandoned import is
      self-correcting in both directions.

## 6. Staged-byte release

- [x] 6.1 `ports/StagedBytes` — `release(paths)`; implementation in `:adapter:ios:app-only`; fake in
      `:adapter:generic:fake`.
- [x] 6.2 Release after the confirming write commits — from a successful import **and** from a
      `present` verdict — and drop that asset's resource rows. Best-effort (`runCatching`).
- [x] 6.3 Release before a prune, at **both** call sites: `DownloadController.onLeaveOrSwitch` and
      `ResetDeviceState` (which prunes the store directly and cannot call into another feature).
- [x] 6.4 The self-extinguishing backlog pass over confirmed assets that still hold resource rows.
- [x] 6.5 Tests: bytes survive `Failed`, `TimedOut`, and an absent-triggered re-import; bytes are
      released on confirmation and before a prune; the backlog pass finds nothing on a second run.
      `InterruptedImportIntegrationTest` is now **5 cases, all green**. The world gained a real
      staging "disk" (the fake transport writes destinations into it), so these assert bytes
      actually survived or vanished rather than that a release call happened.

## 7. Close the loop

- [x] 7.1 The `:test:integration` test from 1.3 now passes; add the leave/switch variant (interrupted
      import → leave → re-drive → still exactly one import, still no orphan object). **7 cases green.**
- [x] 7.2 Assert download progress settles — `downloads_imported` reaches `downloads_assets` — or the
      never-settling counter ships silently. Asserted with a precondition that progress is
      genuinely short first, so it cannot pass for the wrong reason.
- [x] 7.3 `ResetDeviceState` KDoc: its reasoning already argues this for imported rows and stops one
      step short. Correct it to markers.
- [x] 7.4 `./gradlew build` green (`:test:architecture` gates included), and
      `./gradlew compileIosMainKotlinMetadata` for the iOS-only sources. **Both green.**
- [x] 7.5 `./gradlew architectureDiagrams` — a new port changes the port × adapter matrix, and the
      diagrams check is required. `DiagramFreshnessTest` caught it exactly as predicted;
      regenerated — `architecture/di.md` gained the two new adapter rows.

## Not in this change

- The six other ungated `PhotoKit` reads under a partial grant — measured not to be a storm risk; see
  `correct-limited-access-read-premise`.
- Any repair for duplicates already in the wild: identity is `(sourceDeviceId, sourceAssetId)` with no
  content-level dedup anywhere, and imports are terminal on every member. No mechanism exists.
- Staged files orphaned by prunes that ran **before** this change. A directory sweep could reclaim them
  safely — rows always precede files (`plan` runs before `enqueue`), so a keep-set derived from **rows**
  covers in-flight files and anything matching no row is a genuine orphan. Left out on **size**, not
  safety: releasing before prune means nothing new is orphaned, so this only ever reclaims orphans from
  past leaves and switches, and it costs a directory-listing port operation to do it. Revisit if field
  dumps show staging holding bytes the backlog pass cannot account for.
