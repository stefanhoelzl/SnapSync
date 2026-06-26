## 1. Implement the launch-env trigger (`:app:ios`)

- [x] 1.1 Add `SnapSyncRoot.applyLaunchEnvDeeplink()`: read `NSProcessInfo.processInfo.environment["SNAPSYNC_DEEPLINK"]` and forward a present value to `onOpenUrl(it)`, guarded once-per-process via a `by lazy` (no persistence, no `unsetenv`)
- [x] 1.2 Realize the guard from `MainViewController` via a `LaunchedEffect(Unit)` calling `SnapSyncRoot.applyLaunchEnvDeeplink()` (runs after `host` exists; no Swift change)
- [x] 1.3 Confirm no Swift / `iosApp/` change is required and none is made

## 2. Compile check

- [x] 2.1 `./gradlew compileIosMainKotlinMetadata` passes (Linux proxy for the iOS source sets)
- [x] 2.2 `./gradlew build` passes (compiles all targets + JVM tests; no new tests expected — the `onOpenUrl` provisioning path is already covered by `StatusContainerHostTest` and `ConfigDeeplinkTest`)

## 3. On-device verification (gate ①+②)

- [x] 3.1 Push the branch; download the CI dev IPA artifact (`snapsync-dev-ipa-131`) and sideload it (`uvx pymobiledevice3 apps install`) — install succeeded
- [x] 3.2 Launch with a valid event deeplink (`dvt launch … --env SNAPSYNC_DEEPLINK="snapsync://config?v=3&d=eyJ…" --userspace`) — pid 2813
- [x] 3.3 ① Pulled `Documents/debug.log` — confirmed `"applying SNAPSYNC_DEEPLINK launch-env deeplink"` + `"re-provisioned: ledger + discovery cursor reset, extension re-registered"` (delta +1/+1 over baseline)
- [x] 3.4 ② Screenshot — status flipped from "3 images synced" (green) to "0 of 3 images synced · 3 in progress" (amber): ledger reset confirmed
- [x] 3.5 Relaunched with the variable still set — second `applying`/`re-provisioned` pair (delta +2/+2): per-build re-trigger confirmed
- [x] 3.6 Launched once with no `SNAPSYNC_DEEPLINK` — markers unchanged at 2/2: inert path confirmed

## 4. Documentation (root `CLAUDE.md`)

- [x] 4.1 Reframe the *"On-device iOS (manual testing)"* section to "agent-driveable over USB": the `--userspace` + Python ≥3.14 unlock (no root, auto-DDI-mount)
- [x] 4.2 Add the proven command set: install · `dvt launch` · `dvt screenshot` · `SNAPSYNC_DEEPLINK` event-subscribe · pull `debug.log`/crashes · syslog
- [x] 4.3 Document the headless loop and what stays gated (taps need a signed WebDriverAgent; `processJobs()` timing is OS-owned); cross-reference the CI dev-IPA build. Leave `docs/design.md` untouched

## 5. Ship

- [ ] 5.1 One atomic PR (spec + `:app:ios` code + `CLAUDE.md`); verify gate ①+② green on device before merging
- [ ] 5.2 `/ship`, then archive the change (`/opsx:archive`)
