## 1. The naming rule (`model/`)

- [x] 1.1 Add `ReceivedPhotoName` in `domain/model` (design D2, D3):
  - `token(ref)`: FNV-1a 64 over `"<sourceDeviceId>/<sourceAssetId>"`, truncated to 50 bits, base32 lowercase,
    10 characters;
  - `mark(originalFilename, resourceKey, ref)`;
  - `tokenOf(filename)`: rightmost `.snapsync-<token>` anywhere in the stem, case-insensitive.

  Verify with a `commonTest`, run as `./gradlew :domain:model:jvmTest`, covering:
  - golden tokens pinned for fixed refs;
  - a sender name that already carries a mark is re-marked, not double-marked;
  - an empty sender name becomes `snapsync-<token>.<ext>`;
  - `tokenOf` survives ` (1)` and appended text, and returns null on unmarked names.
- [x] 1.2 Retire `importFilename` in favour of `ReceivedPhotoName.mark`. Move its KDoc rationale (the PhotoKit
  default-name trap) onto the new rule, and keep `RawAssetMappingTest` and every other caller compiling.
  Verify with `./gradlew compileIosMainKotlinMetadata :domain:model:jvmTest`.

## 2. Marked imports on both platforms

- [ ] 2.1 iOS `IosPhotoLibraryImporter`: name every resource with `mark(...)` for the request's `ref`, so a Live
  Photo's video gets the same token. Verify with `./gradlew compileIosMainKotlinMetadata` and a rig-recorded or
  simulator run of the gallery-import contract (2.4).
- [ ] 2.2 Android `MediaStoreImport`: set `DISPLAY_NAME` to `mark(...)`. Verify with
  `./gradlew :adapter:android:compileDebugKotlinAndroid` (or the module's compile task) and the Android device
  test in 2.4.
- [x] 2.3 Mocks: `InMemoryGallery` / `PhotoLibraryMock` store the name the import supplies, so the JVM world
  shows marked names. Verify with `./gradlew :adapter:generic:mock:jvmTest`.
- [ ] 2.4 `:test:contracts` gallery-import contract: add a clause that an imported asset reads back, through the
  reader, with a primary `originalFilename` whose `tokenOf` equals the request ref's token. Bind it on the mock
  (JVM), the simulator app (`IOS_SIM_APP`, live in ios-contracts), and Android (`ANDROID_EMU`, via
  `scripts/android-device-tests`). Verify that `ContractCoverageTest` passes in `./gradlew build` and the clause
  runs green on those hosts.

## 3. Adoption at join

- [x] 3.1 Add the download-store write `adoptImported` (design D7): `INSERT OR IGNORE` of an `IMPORTED` row with
  `createdLocalId` and `eventId`, and no resource rows. Expose it on `DownloadService`. Verify with the storage
  contract clause in `DownloadStoreContract`, covering:
  - it inserts a terminal row that `suppressedLocalIds` returns;
  - it never overwrites an existing row.

  Run it on the JVM (`./gradlew :adapter:generic:app:jvmTest`) and on the iOS kexe binding.
- [x] 3.2 Add a gallery service in `:domain:services` (design D5). Given the event window and a set of known
  local ids, it returns `token → AssetId` for marked candidates:
  - under a full grant: a window-narrowed facts read, then `resources(ids)`;
  - under a partial grant: the candidates come from the latest selection snapshot;
  - with no usable grant: empty.

  Verify with a mock-driven service test in `:adapter:generic:mock` `commonTest` covering full, partial and
  denied grants, and that known ids are never resource-read.
- [x] 3.3 Add `ReceivedPhotoAdoption` in `feature/membership` (design D4):
  - read the union (best-effort; a failure logs and returns);
  - compute the foreign refs with no store row;
  - ask the 3.2 service;
  - record each token match with `adoptImported`, tagged with the event.

  Verify with a `:test:feature` test covering: an adopted match, an unknown token ignored, an own-device ref
  never adopted, a union failure that doesn't throw, and an existing row left untouched.
- [x] 3.4 Wire adoption into `MembershipEntry.enter` after `loadShareSet` and before `saveConfig`, for every
  direction. Build it in `compose/`, and update `MembershipEntry`'s ordering test and KDoc. Regenerate
  `architecture/` with `./gradlew architectureDiagrams`. Verify the ordering test passes and the `diagrams`
  freshness test (`:tools:diagrams:test`) is green.
- [x] 3.5 Check the complexity tiers and gates: `./gradlew build` is green with no ceiling raised.

## 4. Observable outcomes (integration)

- [x] 4.1 `:test:integration` (JVM rig host), using a reinstall: the app's private and App-Group stores are
  wiped while the photo library and secure store are kept. Add the verb to the rig vocabulary if none exists,
  and pin it in `RigVocabulary`. Cover:
  - a receiving member reinstalls and rejoins: the received photos are not imported again (library count
    unchanged) and none is uploaded;
  - a share-only rejoin uploads none of the received photos;
  - a photo deleted before the reinstall arrives again;
  - a received photo's filename in the library has the form `IMG_4471.snapsync-….HEIC`.

  Verify by running `./gradlew :test:integration:test`.
- [x] 4.2 Docs: `docs/architecture.md`'s download-store / suppression section explains that the mark is a second
  record rebuilt at join (not a contract). Verify it names the gates and tests from 1–3 accurately.

## 6. Adoption when the grant becomes usable (found on the SE2, design D4/D7)

- [x] 6.1 `device/reinstall` resets the library grant to `NOT_DETERMINED` and leaves the dialog unanswered;
  `ReinstallIntegrationTest` grants only after the rejoin. Verify it reproduces the device failure first (it
  did: 2 shared back, 1 duplicate).
- [x] 6.2 `DownloadController.libraryWritable` (required, no default): nothing imports without a usable grant.
  Verify with `DownloadControllerTest` (`nothing_imports_without_a_usable_grant_…`).
- [x] 6.3 `adoptPending` statement + `DownloadService.adoptAll` settles unmarked planned/staged rows;
  `DownloadController.settleAdopted` under the mutex, skipping claimed refs; `ReceivedPhotoAdoption.record`.
  Verify with `DownloadStoreContract` (`adoption settles a planned row and leaves a marked or terminal one`),
  `DownloadControllerTest` (`an_adopted_ref_is_never_imported`) and `ReceivedPhotoAdoptionTest`.
- [x] 6.4 Adopt in the permission subscription before `uploadTransitions.onPermissionChanged()`. Verify with
  `ReinstallIntegrationTest` green (it logs `adopted 0` at the join, then `adopted 2` at the grant),
  `CompositionSeamTest` and `./gradlew build`.
- [x] 6.5 Android device tests pin the marked `DISPLAY_NAME` (`AndroidImportContractTest`).

## 5. On-device verification (before archive)

- [ ] 5.1 On the SE2 with a full grant:
  - ⚠️ 2026-09-30 run FAILED: after a reinstall iOS resets the photo grant, so the join provisions under
    `NOT_DETERMINED` and adoption reads nothing (`adopted 0 … 0 marked in window`); all 4 received photos were
    downloaded again and the two ≥3 MP ones were shared back. Needs a design change before re-running.
  - receive photos, delete and reinstall the app, rejoin: no duplicates, and nothing is shared back;
  - record the join latency with a large in-window library (design risk).

  Verify from the `debug.log` adoption line and the library count, over the `rig-channel` skill.
- [ ] 5.2 Check that the marked filename survives an edit in Photos and an iCloud Photos sync to a second
  device, and that the limited selection resets on reinstall. Record the findings in design.md's Risks, and
  confirm the spec's gap list still holds.
