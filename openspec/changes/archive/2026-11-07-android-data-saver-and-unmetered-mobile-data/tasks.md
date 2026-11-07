# Tasks

## 1. Confirm the platform facts the design rests on

- [x] 1.1 Confirm from Android's documentation and the JobScheduler source whether a network with
  `NET_CAPABILITY_TEMPORARILY_NOT_METERED` satisfies `NETWORK_TYPE_UNMETERED`, which DownloadManager's held downloads
  and WorkManager's `NetworkType.UNMETERED` both rely on. It does not (design D5, sources there); the user chose to
  count such a network as metered.
- [x] 1.2 On the API 36 emulator, measure from the shell (`cmd netpolicy set restrict-background true|false`,
  `cmd netpolicy add|remove restrict-background-whitelist <uid>`) what `getRestrictBackgroundStatus()` answers for the
  device-test APK, and whether `ACTION_RESTRICT_BACKGROUND_CHANGED` reaches a receiver registered by a process in the
  foreground and in the background. Record the measurement in `AndroidNetworkMonitor`'s KDoc.

## 2. The network monitor reads Data Saver (D1)

- [x] 2.1 `AndroidNetworkMonitor`: restricted when metered (not `NOT_METERED`; `TEMPORARILY_NOT_METERED` no longer
  counts, design D5, its sources in the KDoc) or when Data Saver is `ENABLED` for the app; re-read at every capability
  change; republish on `ACTION_RESTRICT_BACKGROUND_CHANGED`. Verify with new named tests in
  `AndroidNetworkMonitorContractTest`: Data Saver on an unmetered Wi-Fi reads `Online(restricted = true)`; with SnapSync
  exempted it reads `Online(restricted = false)`; switching Data Saver off publishes `Online(restricted = false)` on a
  running collection. Each test restores Data Saver and the exemption, whatever it did.
- [x] 2.2 Bind `NetworkMonitorContract`'s `RESTRICTED` state a second way on `ANDROID_EMU` (Data Saver on an unmetered
  Wi-Fi), so the clause runs over both entries. Verify the binding's run passes.
- [x] 2.3 Pin the temporarily unmetered mapping: a named test over `NetworkCapabilities.Builder` (cellular with
  `TEMPORARILY_NOT_METERED`) reads restricted. Verify it passes on the emulator.
- [x] 2.4 Claim, with one line per split: "Low Data Mode counts as mobile data" (Android's part) and "Data Saver holds
  photos on an ordinary Wi-Fi" on 2.1's first test, "SnapSync allowed unrestricted data" on 2.1's second, "Mobile data
  the carrier does not charge for" on 2.3's. Verify `./gradlew :test:architecture:verifiesReport` lists them claimed.

## 3. Uploads follow the monitor (D2)

- [x] 3.1 Add a named test to `AndroidWorkContractTest`: an `UNRESTRICTED_ONLY` upload under Data Saver on an unmetered
  Wi-Fi sends nothing, and completes once Data Saver is switched off. Verify it passes on the emulator; no production
  change is expected.

## 4. Downloads park while Data Saver is in force (D3)

- [x] 4.1 `AndroidDownload`: under `UNRESTRICTED_ONLY` with Data Saver in force, park the request in the adapter's own
  preferences instead of enqueuing it; enqueue every parked request once the monitor reads unrestricted (by
  collection while the process lives, and at the adapter's start); `cancelAll` drops parked requests. KDoc states why
  (DownloadManager applies Data Saver to metered networks only).
- [x] 4.2 Add a Data-Saver-on-unmetered-Wi-Fi state to `DownloadContract`'s Android binding, or a named test in
  `AndroidDownloadContractTest` where the shared clause cannot express it: an `UNRESTRICTED_ONLY` download does not
  finish while Data Saver is on; it finishes once Data Saver is off; a parked request survives a new adapter instance
  (the process-death proxy) and finishes once unrestricted; `cancelAll` drops a parked request so it never finishes.
  Verify on the emulator.
- [x] 4.3 Verify the mock's download binding still satisfies `DownloadContract` (`./gradlew build`), and that no new
  state left the mock behind.

## 5. The heartbeat and the whole path (D4)

- [x] 5.1 Document in `AndroidWake`'s KDoc that `NetworkType.UNMETERED` does not see Data Saver and why the heartbeat
  still fires on an unmetered Wi-Fi under it (design D4). Verify by reading the KDoc; no code change.
- [x] 5.2 On the emulator with the rig build and a local backend (`snapsync-android`, `local-backend`), mobile data
  off, Data Saver on, on the emulator's Wi-Fi: a photo taken and a photo another member shares both wait, and the line
  reads "Waiting for Wi-Fi…"; Data Saver switched off with the app in the background, both travel by the next
  heartbeat. Record the observed delay in the PR.

## 6. Integration checks

- [x] 6.1 `./gradlew build` passes, and `./gradlew :adapter:android:connectedAndroidDeviceTest` passes on the emulator
  with every new test.
- [x] 6.2 `./gradlew :test:architecture:verifiesReport` shows every scenario of the modified requirement claimed.
  Every scenario of it is claimed. "A hotspot counts as mobile data", which this change leaves unchanged, is claimed by
  phase 37's adapter claims, which ship in the same PR (#533).
