> **Spike ran on ssh-mac (2026-07-15, `macos-26`, Xcode 26.5) and PASSED** — the full-scheme simulator
> build succeeds with the extension in the closure. The only remaining item is **5.1** (retrieve/eyeball
> the composited artifacts): its natural home is a live `screenshots.yml` dispatch once the branch is
> pushed — a mid-spike attempt to capture on ssh-mac was cut off when the cloudflared quick tunnel
> dropped during simulator boot, so the PNGs were not pulled back. Everything else is implemented and
> verified (`:domain:presentation:jvmTest` + `compileIosMainKotlinMetadata` + the spike build).

## 1. Spike: simulator build feasibility

- [x] 1.1 Ran `xcodebuild -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build` (Debug) on ssh-mac → `** BUILD SUCCEEDED **`, `EXIT=0`, with the `BackgroundUploadExtension` in the closure; produced `SnapSync.app` (`app.snapsync`). Verified the runner has the iPhone 16 Pro Max device type + iOS 26.5 runtime.
- [x] 1.2 Not needed — the full scheme builds for the simulator, so no app-target-only fallback is required. The fallback stays documented in the workflow as a hedge.

## 2. Forge factory (`:domain:presentation`, tested)

- [x] 2.1 Add any missing trivial constant sources to `commonMain` main (a `SyncStatusSource` emitting a fixed `SyncStatus.Ready`, and constant `PermissionStatusSource` / `ConfigSource`), alongside the existing `AlwaysAttested` / `InMemoryDownloadStatusSource` / `NoOpEventCreator` — added as private `Const*`/`NoOp*` sources in `ForgeStatusHost.kt`
- [x] 2.2 Add a forge factory (`forgeStatusHost`) that maps a recognized state name → forged sources → a configured `StatusContainerHost`, forging only permission/config/sync-status and relying on the benign default `attestedSource`/`downloadSource`; recognized states: `create` (`CreateEvent`), `joining` (`Joined` + invite QR + event name, STATIC non-pulsing arrow), `in_sync` (`Joined(InSync)` + event name)
- [x] 2.3 Have the factory reject unrecognized names (returns `null` — the signal the shell falls back on)
- [x] 2.4 Add `commonTest` tests (`ForgeStatusHostTest`) asserting each recognized name yields the intended `container.stateFlow` frame (incl. `in_sync` → `Joined(InSync)` with no backend/attestation/photo access) and that an unrecognized name is rejected — passes on JVM (`iosSimulatorArm64` on CI)

## 3. iOS shell wiring (`:app:ios`, wiring-only)

- [x] 3.1 Read `SNAPSYNC_FORGE_STATE` once per process in `SnapSyncRoot.renderHost` (a `by lazy`, mirroring `SNAPSYNC_DEEPLINK`); when set to a recognized state, mount the factory's `StatusContainerHost` instead of the live `host`
- [x] 3.2 When absent or unrecognized, `renderHost` resolves to the live `host` unchanged (`?:` never touches the live stack while forging); `MainViewController` renders `renderHost`, and the `by lazy` ensures no re-apply on view/VC recreation
- [x] 3.3 Verified the Linux proxy compile: `./gradlew compileIosMainKotlinMetadata` (BUILD SUCCESSFUL)

## 4. Screenshot CI workflow (`.github/workflows/screenshots.yml`)

- [x] 4.1 Created a `workflow_dispatch`, non-gating workflow on `macos-26` with a header comment capturing the rationale (6.9″ = App Store minimum; the three states; brand band; artifact-not-auto-upload; non-gating; ssh-mac precedent)
- [x] 4.2 Build invocation confirmed by the spike — the full `iosApp` scheme (`-sdk iphonesimulator`, `CODE_SIGNING_ALLOWED=NO`); the workflow boots an iPhone 16 Pro Max (device type + iOS 26.5 runtime verified present) and `simctl install`s the produced `SnapSync.app`
- [x] 4.3 Apply `simctl status_bar override --time 9:41 --batteryState charged --batteryLevel 100 --cellularBars 4 --wifiBars 3`
- [x] 4.4 For each state in {`create`, `joining`, `in_sync`}: `simctl launch` with `SIMCTL_CHILD_SNAPSYNC_FORGE_STATE=<state>` → `simctl io screenshot` → terminate before the next launch
- [x] 4.5 Composite each raw shot onto a brand-green (`#0E9D6B`) 1320×2868 canvas with its placeholder headline via ImageMagick
- [x] 4.6 Upload the composited set as a workflow artifact (`en-US/6.9/*.png`)

## 5. Validation & docs

- [ ] 5.1 Dispatch the workflow and confirm three composited artifacts are produced and legible (macOS-gated)
- [x] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes (47/0)
- [x] 5.3 Documented the new `SNAPSYNC_FORGE_STATE` dev launch-env trigger in root `CLAUDE.md` alongside `SNAPSYNC_DEEPLINK` / the seeders
