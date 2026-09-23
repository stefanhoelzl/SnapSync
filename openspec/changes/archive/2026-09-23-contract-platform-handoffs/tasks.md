## 1. Ports and core

- [x] 1.1 Add `Handoff` (`Accepted` | `Refused(reason)`) to `:domain:ports`; make `SharePresenter.share` and `LinkOpener.open` `suspend` and answer it; the `None` instances answer `Refused`; rewrite the port and `PlatformHandoff` docs from "fire-and-forget" to "answers, nothing acts on it"
- [x] 1.2 In `snapSyncApp`, render the answer on the `tap.share` / `tap.openLink` lines and log a refusal at `Error`, without adding an `AppCore` member (`detektComposeTier` stays at its ceiling)
- [x] 1.3 Update `JoinSeamsTest` for the answering ports

## 2. iOS adapters

- [x] 2.1 `IosLinkOpener`: call `openURL(_:options:completionHandler:)` through the `internal` `UrlOpenerApi` seam, which owns the main-queue hop; an unparseable URL answers `Refused` without a platform call
- [x] 2.2 `IosShareSheet`: answer `Accepted` from the presentation completion and `Refused` when there is no key window

## 3. Contracts

- [x] 3.1 `:test:contracts`: `LinkOpenerContract` (`UNCLAIMED_URL_IS_REFUSED`, then `CLAIMED_URL_IS_ACCEPTED` last), `SharePresenterContract` (`PRESENTABLE_SHARE_IS_ACCEPTED`), and the real-clock `withinRealTime` bound
- [x] 3.2 `:adapter:generic:fake` commonTest: bind `LinkOpener.None` as `Fake`, reaching `UNCLAIMED`
- [x] 3.3 `:adapter:ios:app-only` rig source set: recorder/replayer for `UrlOpenerApi`, the device `LinkOpener` binding and registry, the simulator-app `LinkOpener` and `SharePresenter` bindings (disposal dismisses the sheet), registered in `simulatorAppContracts()`
- [x] 3.4 `:adapter:ios:app-only`: embed the committed recordings into `iosTest`, and add `IosLinkOpenerReplayContractTest`
- [x] 3.5 `:test:rig`: register the device registry in `Boot`, and look contracts up by the current host first
- [x] 3.6 `MainLaneContainmentTest`: allowlist the rig binding that dismisses the sheet, with its reason

## 4. Device recording

- [x] 4.1 Record `LinkOpener` on the SE2 over the rig and commit `test/contracts/recordings/LinkOpener@IOS_DEVICE_APP.rec` unedited; both live lines read `Passed`
- [x] 4.2 Re-record if any task above changes the call `UrlOpenerApi` renders (none did: the adapter and the tape are unchanged since the recording)

## 5. Verification

- [x] 5.1 `./gradlew build` green (incl. `ContractCoverageTest`, detekt tiers, `MainLaneContainmentTest`); `architecture/` regenerated and committed
- [ ] 5.2 CI: the macOS job runs `IosLinkOpenerReplayContractTest` green, and the `ios-contracts` job runs `LinkOpener` and `SharePresenter` in the simulator app green
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and the change validate
