# Tasks

## 1. The membership's choice

- [x] 1.1 Add `mobileData: Boolean = true` to `EventConfig` (KDoc: capability `mobile-data`, default = today's behaviour) and `TransferNetwork { ANY, UNRESTRICTED_ONLY }` with its derivation from the config in `model/`; verify with a `model/` commonTest that an old config JSON without the field decodes to `ANY`
- [x] 1.2 Carry the choice through the join form and `confirmJoin` (`JoinChoice`/`RangeForm`/`UserCommands`) and the reconfigure save, written into `EventConfig` like `saveToAlbum`; verify with feature tests (`:test:feature`) that join and settings save persist it and that a failed save applies nothing
- [x] 1.3 Expose it on the control channel (`/user/confirmJoin?mobileData=`, the settings command) and in `/device/state`'s form; verify through a `:test:control` test on the JVM host

## 2. Network state: restricted

- [x] 2.1 Turn `NetworkAccess` into `Online(restricted) | Offline | Blocked` and update every `when` the `network-connection` branch added (the offline banner ignores the flag); verify `./gradlew build` is green
- [x] 2.2 `IosNetworkMonitor`: restricted = `nw_path_is_expensive || nw_path_is_constrained` through the `NetworkPathApi` seam; verify with `IosNetworkMonitorTest` cases for expensive, constrained and neither
- [x] 2.3 `AndroidNetworkMonitor`: restricted = default network lacks `NET_CAPABILITY_NOT_METERED`, or Data Saver is on while metered; verify with its device test on the emulator (`cmd netpolicy set metered-network AndroidWifi true`, `cmd netpolicy set restrict-background true`)
- [x] 2.4 `NetworkMock` lever for restricted, and `NetworkMonitorContract` clauses for restricted/unrestricted; verify `ContractCoverageTest` passes with the emulator binding live and the SE2 recordings (Low Data, hotspot) committed via `POST /contract/NetworkMonitor`

## 3. Transfers carry the rule

- [x] 3.1 `UploadTarget.network` and `Download.start(url, tag, network)`, filled from the config when a transfer is created (`UploadTransferService` create + retry, `DownloadJobs`); verify with service tests over the mocks that a transfer created after a settings change carries the new rule and an earlier one keeps its own
- [x] 3.2 Mocks: `UploadQueueMock`/`UploadSessionMock`/`DownloadSessionMock` record each transfer's rule and refuse to complete an `UNRESTRICTED_ONLY` transfer while the `NetworkMock` is restricted; verify with the mocks' contract bindings
- [x] 3.3 iOS: `uploadUrlRequest` sets `allowsCellularAccess`/`allowsExpensiveNetworkAccess`/`allowsConstrainedNetworkAccess` from the rule; `IosDownload.start` builds a request with the same flags; verify with `compileIosMainKotlinMetadata` and iosTest unit tests of the request builders
- [x] 3.4 Android downloads: `AndroidDownload` maps `UNRESTRICTED_ONLY` to `setAllowedOverMetered(false)`; verify with a `DownloadContract` clause live on `ANDROID_EMU` (held on metered Wi-Fi and cellular, completes on unmetered)
- [x] 3.5 Android uploads: `AndroidUpload` holds an `UNRESTRICTED_ONLY` PUT until the default network is unmetered and not Data-Saver-restricted; verify with an `UploadContract` clause live on `ANDROID_EMU` that reaches the fixture over a real (non-loopback) route, so metering applies
- [ ] 3.6 Record the iOS `Upload`/`Download` restricted clauses on the SE2 (Low Data Mode, hotspot) and commit the recordings unedited; verify the replay passes in `iosPlatformTest`
- [x] 3.7 Update KDoc of `TransferSessions.kt` (its "run now, on whatever network you have" rationale) and `IosDownload` to name the per-request rule; verify by review

## 4. Resuming on Wi-Fi

- [x] 4.1 `WakeTrigger.After.requiresNetwork` → requirement `NONE | ANY | UNRESTRICTED`; `AndroidWake` maps `UNRESTRICTED` to `NetworkType.UNMETERED`, `IosWake` to `requiresNetworkConnectivity = true`; verify with `IosWakeTest` and a `WakeContract` clause on `ANDROID_EMU` (unsatisfied while metered)
- [x] 4.2 The upload flow schedules its wake with `UNRESTRICTED` while the choice is off and uploads remain; verify with a flow test over the mocks and an integration test (`rigTest`) in which a held upload completes after the network turns unrestricted with no foreground

## 5. Join, settings and status UI

- [x] 5.1 Join and settings screens: the mobile-data checkbox below the album choice, on by default, with its note when off ("photos are sent and received only on Wi-Fi"); verify with `:ui:screens` click tests and the `statusActions` table
- [x] 5.2 Status read-model: waiting = arrow shown, none pulsing, choice off, `Online(restricted = true)`; `UiState` carries it and `AppStatusLine` shows "Waiting for Wi-Fi…"; verify with presentation tests and a `:ui:screens` render test, and that `ReadModelImportsTest` still passes
- [x] 5.3 World harness: inspector lever for the network (restricted / unrestricted / offline) so every new state is reachable without a device; verify by driving it headlessly with the `ui-harness` skill and reading back the waiting line

## 6. Integration and docs

- [x] 6.1 Integration tests (`:test:integration`, through the rig on the JVM host) for each `mobile-data` scenario: untouched choice uses any network; off holds both directions on a restricted network and releases them when unrestricted; a change governs only later transfers; join/rename/settings keep working while restricted; verify `./gradlew build`
- [x] 6.2 `docs/architecture.md` / `docs/testing.md`: the transfer rule's path through the ports and the new contract clauses; verify by review
- [x] 6.3 Regenerate `architecture/` (`./gradlew architectureDiagrams`) and commit; verify the freshness test passes
- [ ] 6.4 On-device check of the whole feature on the SE2 (Low Data Mode, hotspot) and the A40 (mobile data), once `rig-ios18-crash` also lets the XS run a rig build: join with mobile data off, take a photo, see "Waiting for Wi-Fi…", reach Wi-Fi, see it shared; record the outcome in the PR
