## 1. The seam carries the un-counted value

- [x] 1.1 Change `GalleryStatusSource.size` to `StateFlow<Int?>` in `:domain` `ports/`, and rewrite its
  KDoc: `null` is "the count has not been taken", every `Int` is a count someone took, and the two are
  never conflated.
- [x] 1.2 `OwnDeviceGalleryStatusSource`: seed `_size` with `null`; keep the `SelectionPolicy.None`
  branch publishing an explicit **counted** `0` before any bound is read (design D3). Update the class
  KDoc, which currently documents the seeded `0`.
- [x] 1.3 `InMemoryGalleryStatusSource` (`:adapter:generic:fake`): cell becomes `MutableStateFlow<Int?>`,
  secondary constructor defaults to `null` (design D6). Verify `FakeHonestyTest` still passes — the
  surface stays "port contract plus a constructor taking initial state".

## 2. The other two counts carry it too

- [x] 2.1 `LedgerCounts` gains read-ness (a `read: Boolean` with an `UNREAD` companion value);
  `ReadingLedgerCountsSource` seeds `UNREAD` and publishes `read = true` on every successful read,
  including a genuine `(0, 0)`. A failed read still retains the last good value — which, before any
  success, is `UNREAD`.
- [x] 2.2 `MutableLedgerCountsSource` (the harness/test fake) publishes read values from `set(…)` and
  seeds `UNREAD` when constructed without an initial value.
- [x] 2.3 `DownloadProgress` gains the same read-ness; `InMemoryDownloadStatusSource` seeds un-read and
  the real source publishes read values on every successful refresh.

## 3. The projection waits for reads, not for seeds

- [x] 3.1 `LedgerBackedSyncStatusSource`: emit `SyncStatus.Loading` while the gallery size is `null`
  **or** the ledger counts are un-read; emit `Ready` only once every input has been read. Keep
  `SyncProgress`'s fields non-nullable — the un-read state lives in `Loading`, not inside a snapshot.
  Rewrite the factory KDoc, which currently describes the seeded-zero combine.
- [x] 3.2 `StatusContainerHost`: hold the joined health at `SyncHealth.Loading` while the download
  projection is un-read, so an un-read download arm cannot hide its arrow and carry the screen to
  `InSync` (design D2). Leave `syncHealth`'s arrow derivation itself unchanged — it is correct once its
  inputs are real.

## 4. The foreground flow stops gating the refresh

- [x] 4.1 `Foreground.run()`: move `statusPoller.start()` above the pump and `pumpForeground()` into the
  existing `coroutineScope { … }` as one more `launch`. Keep `reloadConfig()` first and
  `refreshAttestation()` second. Update the KDoc, including the claim that "the refreshStatus launch
  below covers 'now'".
- [x] 4.2 `SnapSyncApp.refreshStatusSources()`: run `ledgerCounts.refresh()` and
  `downloadStatusSource.refresh()` **before** the gallery enumeration, and wrap the enumeration in
  `runCatching` with an `Error`-severity log naming the consequence (`N` stays unknown). A failure must
  not cancel the sibling launches.

## 5. Consumers and harnesses

- [x] 5.1 Update every remaining consumer the compiler flags: `:test:world` (`World.ownGallery` and the
  gallery wrappers), `:app:desktop`'s forge presets and world inspector — each passing a real `Int`
  wherever it means a count, so preset meanings are unchanged.
- [x] 5.2 Update existing tests that construct the fakes with an implied zero, so they state the count
  they mean rather than inheriting one.

## 6. Pin it

- [x] 6.1 `:test:integration` (`commonTest`, so it runs on JVM **and** `iosSimulatorArm64`): compose the
  real core via `snapSyncApp` over `:test:world`, join a membership with photos, and assert the joined
  health is `SyncHealth.Loading` before any status refresh and `SyncHealth.Syncing` after — never
  `InSync` in between. This is the test that would have caught the defect.
- [x] 6.2 `:test:integration`: a counted-zero membership (a non-contributing policy with its downloads
  read and complete) still reaches `SyncHealth.InSync`.
- [x] 6.3 `Foreground` unit test in `:domain` `commonTest`: with a `pumpForeground` that never returns,
  assert `refreshStatus` still ran and the poller was started.
- [x] 6.4 `LedgerBackedSyncStatusSource` unit test: three seeded-but-unread inputs produce no `Ready`;
  a read `(0, 0)` with a counted `0` total does.
- [x] 6.5 `OwnDeviceGalleryStatusSource` unit test: never refreshed → `null`; `SelectionPolicy.None`
  refresh → counted `0` with no enumeration; a throwing enumeration → value unchanged, no publish.

## 7. Verify

- [x] 7.1 `./gradlew build` green (compiles all targets, runs the JVM tests, and gates on
  `:test:architecture` + `detektAppShell`).
- [x] 7.2 `./gradlew compileIosMainKotlinMetadata` green — the Linux-runnable proxy for the iOS source
  sets.
- [x] 7.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green (structure only; it
  does not check truth).
- [x] 7.4 `./gradlew architectureDiagrams` and commit if anything moved — stale `architecture/` blocks
  the PR. No module or zone boundary should change, so expect a no-op.
- [x] 7.5 Drive the forge harness (`ui-harness` skill) and confirm the neutral "Syncing…" line renders
  where a cold launch now lands, and that the `in_sync` preset is unchanged.

## 8. Ship

- [ ] 8.1 Branch → PR → `/ship` with the `bug` changelog label.
