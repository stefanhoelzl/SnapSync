## 1. Resolve the blocking open question

- [x] 1.1 Confirm the scoped-`fullEnumeration` pruning hazard is vacuous. **Resolved**: `UploadCycle.kt:188`
      passes `liveResources` — the post-suppression, post-cutoff-filter set — to `ledger.retainAssets`,
      never the raw walk output. The authoritative `commonMain` filter sits between the walk and the
      reconcile, so bounding the walk leaves `retainAssets`'s input identical. Vacuous by construction;
      the three-claim chain was unnecessary. design.md updated.
- [x] 1.2 If 1.1 does **not** hold, stop and revise the design. **Not triggered** — 1.1 holds.

## 2. Make the cutoff required in the config layer

- [x] 2.1 Change `EventConfig.minPhotoDate` to a non-null `String` with **no default**
      (`capability/config/.../EventConfig.kt`). Leave `EventLinkPayload.minPhotoDate` nullable — the wire
      key stays an absent-by-default dev/test override.
- [x] 2.2 In `KeychainConfigStore.readConfig()`, let a `MissingFieldException` (legacy item lacking
      `minPhotoDate`) map to `config = null`, and log the decode failure at warn with the entry-point
      prefix. Do **not** substitute a default; assert in review that no `= ""` default is introduced.
      **Also removed** the second legacy path (a pre-name-split bare `snapsync://` deeplink URL item,
      upgraded in place as `EventConfig(eventId = …)`): it cannot supply a cutoff, so it now reads as no
      config too. Added Kermit to `:capability:config`'s `iosMain` for the warn line.
- [x] 2.3 Add `commonTest` coverage: an `EventConfig` JSON without `minPhotoDate` fails to decode; one
      with it round-trips. Runs on JVM and `iosSimulatorArm64`. Also pinned the `>= ""` asymmetry that
      makes an empty-string default unsafe. Rewrote the three legacy-JSON fixtures (direction /
      saveToAlbum / name) to carry a cutoff, so each still tests its own field's default.
      `:capability:config:jvmTest` green.

## 3. Make an empty cutoff unreachable in the join gate

- [x] 3.1 `StatusContainerHost`: seed the join phase's cutoff to **now** (via the injected `Clock`) when
      `JoinLoad.Found.createdAt` is null or `CutoffFormatter.toLocal` returns null.
- [x] 3.2 `StatusContainerHost.autoConfirm`: change `explicitCutoff ?: load.createdAt` to fall back to
      now, so the headless dev launch can never provision an absent cutoff.
- [x] 3.3 `StatusScreen`/`JoiningEventScreen`: seed `chosen` to now rather than null, and gate the Join
      confirm action on `chosen != null` (the invariant guard, independent of the seeding fix).
- [x] 3.4 Apply the same to the switch-confirmation dialog, which takes the new event's default cutoff
      with no picker.
- [x] 3.5 Tests: `StatusContainerHostTest` — absent/unparseable `createdAt` seeds now; a millisecond
      `createdAt` normalizes to second precision; `autoConfirm` with no explicit cutoff and no `createdAt`
      provisions with now. The test formatter is the REAL `SystemCutoffFormatter` on a fixed clock, not a
      fake, so it exercises the actual ISO-8601 codec. `JoinScreenTest` — the commit-failed phase still
      offers a working Retry (it seeds `now`, having never seen a `Ready` phase).

## 4. Drop the null branches in the domain

- [x] 4.1 `UploadCycle.photoCutoff` becomes `suspend () -> String`; delete the
      `if (cutoff == null) unfiltered else …` branch, keeping the filter and its dropped-count log.
- [x] 4.2 `OwnDeviceGalleryStatusSource.photoCutoff` becomes `suspend () -> String`; delete its null
      branch.
- [x] 4.3 Update the composition roots that supply the cutoff: `SnapSyncRoot` (`photoCutoff`),
      `UploadExtensionRoot`, `UrlSessionUploadController`.
- [x] 4.4 Update `commonTest`s that pass a null cutoff (`UploadCycle`, `OwnDeviceGalleryStatusSource`,
      `:test:integration`) to pass a real cutoff; delete tests asserting whole-library-on-null.

## 5. Bound the walk seam

- [x] 5.1 `RawAssetSource`: delete `walkAll()`; add `walk(since: String): List<RawAsset>` alongside the
      existing by-local-identifiers walk. Update `InMemoryRawAssetSource` to filter by `creationDate`.
- [x] 5.2 `GalleryResourceEnumerator.enumerate()` becomes `enumerate(since: String)`; thread the bound
      through `ResourceEnumerator`. Update `InMemoryGalleryResourceEnumerator`.
- [x] 5.3 Update callers: `IosDiscovery.discover()` (full-enumeration branch takes the bound),
      `OwnDeviceGalleryStatusSource.refresh()`.
- [x] 5.4 Update the harness/test fakes and inspector: `:test:world` `UploadFakes`, `World`,
      `app/desktop` `WorldInspectorController`.
- [x] 5.5 `commonTest`: enumerating with a bound excludes pre-bound assets, on JVM and the simulator.

## 6. Narrow the PhotoKit fetch (the inert optimization)

- [x] 6.1 `PhotoLibraryRawAssetSource.walk(since:)`: build `PHFetchOptions` with
      `NSPredicate("creationDate >= %@", …)`, parsing the bound via `NSISO8601DateFormatter`, and pass it
      to `PHAsset.fetchAssetsWithOptions`. Keep the `withContext(Dispatchers.Default)` hop from `28226ec`.
- [x] 6.2 **Widen the predicate by one day** (`bound − 24h`) so predicate/`NSDate` skew can never
      under-return; the authoritative `commonMain` string filter still removes the extras. Comment the
      asymmetry (under-return is unrecoverable, over-return is free).
- [x] 6.3 If the bound fails to parse into an `NSDate`, fall back to an **unpredicated** fetch rather
      than an empty one — over-return, never under-return.
- [x] 6.4 Verify the fan-out mapping tests still pin behavior with no PhotoKit involved, i.e. the
      predicate genuinely cannot change the answer.

## 7. Verify

- [x] 7.1 `./gradlew build` (compiles all targets, runs JVM tests including the Compose Desktop UI tests).
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 7.3 `npx --yes @fission-ai/openspec@1.4.1 validate --changes --strict`.
- [x] 7.4 Drive the join gate over the real full-stack world — **done as an offscreen integration test**,
      not by eye. The GUI cannot be observed here: `java.awt.Robot` captures the X root, and under this
      compositing Wayland session redirected windows are not in it (verified: a pure black frame while the
      harness ran). The forge harness named in the original task renders no join gate at all; that was an
      error in the task.
      Instead, `JoinGateIntegrationTest` now drives the real `engine → status → presentation` stack over
      `:test:world`, and **the world's mini-edge was made faithful**: it minted a tidy
      `createdAt = "2026-01-01T00:00:00Z"` while the real backend mints `new Date().toISOString()` with
      milliseconds. *That is why the integration suite never caught the millisecond bug — the fake backend
      was cleaner than the real one.* `MiniEdge.CREATED_AT` now carries `.000`.
      The new test asserts what 7.4 asked: the loaded phase shows a second-precision cutoff derived from a
      millisecond `createdAt`, and confirming persists exactly the cutoff the surface displayed.
      Mutation-checked: reverting `cutoffOrNow` to verbatim reuse fails it (and fails the pre-existing
      `first_join_loads_details_then_enrolls_and_joins` too).
- [x] 7.5 On device (`ssh-mac` dev IPA → `apps install` → `dvt launch`): **DONE, iPhone SE2 / iOS 26.5.**
      Built the working tree into a dev IPA, installed, and joined three fresh events headlessly via
      `SNAPSYNC_DEEPLINK` (autoJoin).
      **Found and fixed a real bug in the process.** The backend mints `createdAt` with
      `new Date().toISOString()` — always milliseconds (`2026-07-09T19:24:17.182Z`). The join gate reused
      it verbatim, and a bare `NSISO8601DateFormatter` (no `.withFractionalSeconds`) returns nil for it, so
      `fetchOptionsSince` dropped the predicate and fell back to a WHOLE-LIBRARY fetch — silently undoing
      this change on the default path. Fixed by normalizing `createdAt` in `cutoffOrNow` and by teaching
      the iOS walk to parse fractional seconds. Confirmed on device:
      `provisionEvent(… cutoff=2026-07-09T19:24:17Z)` from `createdAt=…17.182Z`.
      **Predicate verified.** The original assertion ("cutoff dropped ~0") is AMBIGUOUS on this device: the
      predicate is widened by one day and the SE2's whole library is one Live Photo taken today, so a
      working predicate and an absent one produce identical logs. Used a decisive probe instead — a
      dev/test `minPhotoDate` one year in the future. Bounded fetch ⇒ `discoverResources = 0 resource(s)
      (13ms)`, `discovered 0`, `cutoff dropped 0`. Unbounded would have been `discovered 2 / cutoff
      dropped 2` (exactly what the pre-change build logs at 16:41:40 and what this build logs at 19:54:18
      when the photo falls inside the 1-day widening). The 13ms vs 275ms delta is the skipped
      `assetResourcesForAsset` round-trip.
      **Re-verified at scale** after seeding 4000 synthetic assets (`SNAPSYNC_SEED_PHOTOS`, below): the
      bounded full enumeration scanned a 4001-asset library in **39ms**, returning only the in-scope photo.
- [x] 7.6 On device: foreground with a large library, confirm no `0x8BADF00D`. **DONE.** Seeded the SE2's
      library to ~5001 assets via a new dev-only `SNAPSYNC_SEED_PHOTOS` launch-env trigger (see 8.4), then
      joined an event with a deliberately wide cutoff (`2000-01-01`) and `direction=download` (so nothing
      uploads), putting **every** asset in scope. Log confirms the whole-library walk was initiated:
      `onForeground(...)` then `provisionEvent(… cutoff=2000-01-01T00:00:00Z)`. The app stayed **ALIVE**
      across a 3-minute liveness sample, and `idevicecrashreport` shows **zero** SnapSync `.ips` dated
      2026-07-09. On the main thread the scene-update budget is 10 s and ~5000 assets cannot finish inside
      it, so this is the `withContext(Dispatchers.Default)` hop (`28226ec`) doing its job.
      **Re-run with the 8.5 log line — now measured, not inferred.** On the same ~5001-asset library:
      ```
      since 2000-01-01T00:00:00Z → enumerated 5004 → N=5002 in 18026ms   (whole library in scope)
      since 2026-07-09T21:35:05Z → enumerated    0 → N=0    in     5ms   (real event cutoff)
      ```
      The unbounded walk takes **18.0 s** — 1.8x the 10 s scene-update budget — and the app survives it,
      which is `withContext(Dispatchers.Default)` (`28226ec`) doing exactly its job. The bounded walk is
      ~3500x cheaper. Zero SnapSync `.ips` dated 2026-07-09 after every run.
      **Separate finding, unrelated to this change:** the SE2 carries a `0x8BADF00D` from 2026-07-05 whose
      faulting thread is `writev` → `NSLog` → `-[UIViewController _endAppearanceTransition:]` inside
      Compose's `ComposeLayersViewController` — a scene-update watchdog kill caused by synchronous logging
      during a view transition, not by PhotoKit. Worth its own investigation.
- [x] 7.7 On device: confirm the **incremental** change-token walk is bounded. Seeded 1000 further
      out-of-scope assets while a change token existed, forcing the incremental branch.
      Before the fix: `discovered 1503 resource(s) (166247ms)`, `cutoff dropped 1503` — the filter did the
      work, 166 s inside a process with a ~3-minute hard cap. After: `discovered 0 resource(s) (343ms)`,
      `cutoff dropped 0`. ~485x faster; the bound, not the filter, now excludes.

## 8. Follow-through

- [x] 8.1 Delete the now-doubly-dead `PhotoLibraryGalleryStatus` (never constructed; its O(1) count and
      `photoLibraryDidChange` observer are superseded), or file the follow-up to restore the live-count
      ding it alone implements.
- [x] 8.2 Fix `CLAUDE.md`'s opening line: SnapSync is event photo sharing, not "a personal one-way iOS
      photo backup". The stale identity is what made the whole-library default look safe.
- [x] 8.4 `SNAPSYNC_SEED_PHOTOS` dev/test launch-env trigger (`DevPhotoSeeder.kt`): fills the photo library
      with N synthetic assets dated from 2001, so the capture-date-bounded walk can be exercised against a
      large library on device. Same inertness argument as `SNAPSYNC_DEEPLINK` (a launch env var is only
      injectable via a developer launch). Treated as dev infrastructure, like the `ssh-mac` workflow, and
      documented in the root `CLAUDE.md` rather than given a spec. **It writes to the photo library** —
      seeded assets need a manual delete (`deleteAssets` always raises a system confirmation), which is why
      they are parked in a single year of the Photos timeline.
- [x] 8.5 Added the enumeration-cost line to `OwnDeviceGalleryStatusSource.refresh`:
      `gallery: enumerated R resource(s) since <cutoff> (P over-returned pre-cutoff, S suppressed) → N=… in Xms`.
      It reports the widened predicate's over-return count too, so the "the fetch predicate may over-return
      but never under-return" contract is observable on device rather than merely asserted. `:domain:status`
      gains a Kermit dependency; the `TimeSource` is injected so the line stays testable.
      This is the log that turned 7.6 from an inference into a measurement.
- [x] 8.3 Archive the change. Delta specs synced into `openspec/specs/` (10 MODIFIED requirements across
      `photo-date-cutoff`, `gallery-status`, `join-event`, `deeplink-config`: +16 scenarios, -3). The three
      removals are exactly the whole-library scenarios this change deletes. `validate --specs --strict`
      passes (44/44).
      **Caught during archive:** the `join-event` delta's MODIFIED block had silently dropped the existing
      scenario `A load failure is retryable` — the "MODIFIED with partial content loses detail at archive
      time" pitfall. Restored before syncing.
