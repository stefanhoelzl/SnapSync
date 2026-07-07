## 1. Direction data model (`:capability:config`)

- [x] 1.1 Add a `Direction` enum (`Both`, `UploadOnly`, `DownloadOnly`) in `:capability:config` `commonMain`, with a `both`/`upload`/`download` wire mapping for the deeplink override.
- [x] 1.2 Add `direction: Direction = Direction.Both` to `EventConfig` (`EventConfig.kt:42`); confirm whole-object serialization + `ignoreUnknownKeys` decode so a legacy item without the field reads as `Both`.
- [x] 1.3 Add optional `direction: String? = null` to `EventLinkPayload` (`EventConfig.kt:22`) and thread it through the strict `decodeConfigUrl`/`encodeConfigUrl` codec (`ConfigDeeplink.kt`), rejecting any `direction` outside `both`/`upload`/`download` and never emitting it from the canonical QR encoder.
- [x] 1.4 Extend the config `commonTest` suite: round-trip `direction` through `EventConfig` (incl. legacy-decode-defaults-to-Both), decode `EventLinkPayload` with/without a valid/invalid `direction` key, and assert the canonical encoder omits it. (Runs on JVM + `iosSimulatorArm64`.)
- [x] 1.5 Confirm `KeychainConfigStore` save/read persist `direction` via whole-object serialization (no field-list edits) and the idempotent-`save` equality now includes `direction`.

## 2. Join capture + provision gating (`:capability:join-event`, `:domain:presentation`, `:domain:ui`)

- [x] 2.1 Add `direction` to `JoinEvent.join(...)` and construct the `EventConfig` with it; enable the producer via the injected `provision` only when `direction` includes upload (gate in `provisionEvent`, `SnapSyncRoot.kt`, which already takes the whole config — plus gate the grant collector `enableBackgroundUploadOnGrant` via `uploadArmEnabled()`, since a pre-join grant fires independently).
- [x] 2.2 Thread a chosen `direction` through the container confirm intents — `onConfirmJoin` / `onConfirmSwitch` / `onRetryJoin` / `commit` / `autoConfirm` — and the `commitJoin` lambda (bound at `SnapSyncRoot.kt`). (Held in Compose local state like `chosenCutoff` rather than seeded on `JoinPhase.Ready`, mirroring the cutoff's actual pattern — the default is the constant `Both`, so nothing data-driven needs to ride on the phase.)
- [x] 2.3 Render the three-way direction control in `JoiningEventScreen` at the reserved slot, held in Compose local state, defaulting to `Both`, passed into `onConfirm`/`onRetryJoin`. Final UI: an **arrows-only** selector (`AppDirectionSelector` + `SyncDirectionChoice` in `:domain:ui:components`) — share ↑ / receive ↓ / both ⇅ (`ArrowUpward`/`ArrowDownward`/`SwapVert`, no leading checkmark) — with a caption above that **adapts to the selection** (the text labels wrapped and read poorly). The screen maps `Direction` ↔ `SyncDirectionChoice` so components stays decoupled from config.
- [x] 2.4 Disable the `CutoffRow` when the selected direction is `DownloadOnly` (visible but inert, via a new `enabled` param); re-enable for `Both`/`UploadOnly`.
- [x] 2.5 Wire the `autoJoin` path (`autoConfirm`) to default `direction = Both` and honor the decoded `EventLinkPayload.direction` override when present.
- [x] 2.6 Extend `JoinEvent`/container `commonTest`: `direction` persisted in the saved config; enrollment fires for download-only; the chosen direction crosses to commit on confirm; autoJoin default-Both and dev-override paths. UI test: segmented control renders + download-only disables the cutoff row.

## 3. Download gate (`:capability:download`)

- [x] 3.1 Inject a `downloadEnabled: () -> Boolean` predicate into `DownloadController` (kept config-free) and early-return from `reconcile` when it is false — the single choke point covers join/foreground/push. Bound in `SnapSyncRoot` to `config.direction.includesDownload`.
- [x] 3.2 Left the `DownloadPushReceiver` active-event guard unchanged; the two guards compose (active-event push on an `UploadOnly` membership → received, controller no-ops).
- [x] 3.3 Extended download `commonTest`: reconcile fetches no union / enqueues nothing when disabled; runs unchanged when enabled.

## 4. Status masking (`:domain:presentation`)

- [x] 4.1 Threaded `config.direction` (already in scope in `reduceFrom`) into `syncHealth`; force `Arrow.HIDDEN` for the excluded direction so `InSync` collapses over the enabled direction(s) only.
- [x] 4.2 Extended presentation `commonTest`: upload-only masks the download arrow (InSync when uploads complete, downloads irrelevant); download-only masks the upload arrow (InSync when imports complete, un-uploaded gallery irrelevant); `Both` unchanged.

## 4b. Full-stack harness reviewability

- [x] 4b.1 The `:app:desktop` world harness previously provisioned a created event **directly** (bypassing the join gate), so the new direction selector was never reachable via create. Wired the harness's `StatusPane` with a **real `JoinEvent` over the world** (`loadJoinDetails`/`commitJoin`) and routed create's `onMinted` into the host's pending-join gate (via `onHostReady` host capture), so create **and** scan now reach the `JoiningEvent` surface (direction + cutoff rows) and confirm enrolls + provisions — matching the iOS app. Added `:capability:join` + `:capability:device-id` deps to `:app:desktop`.

## 5. Copy reword

- [x] 5.1 Reworded the backup-framed copy to sync/share framing. The only user-facing backup string was the iOS system permission prompt `NSPhotoLibraryUsageDescription` in `iosApp/iosApp/Info.plist` (the in-app `NeedsAccess` line was already neutral — "Allow photo access"). The spec's legacy `PermissionBlocked` detail table is reworded in the `sync-status-screen` delta.
- [x] 5.2 No `commonTest`/snapshot assertions pin the old backup copy (grep-confirmed) — nothing to update.

## 6. iOS wiring + verification (`:app:ios`)

- [x] 6.1 Threaded `direction` through the composition-root bindings (`SnapSyncRoot.kt` commitJoin + `provisionEvent` producer gate + grant-collector gate + `DownloadController.downloadEnabled`) with no logic parked in the shell (all decisions are `Direction.includes*` predicates from the tested config capability). Also fixed `StatusPane` (desktop harness) commitJoin arity.
- [x] 6.2 `./gradlew build` (all targets + JVM tests) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) both green.
- [x] 6.3 Extended `:test:integration` (`FullStackIntegrationTest`): upload-only lands own objects + imports no foreign + reads In sync; download-only imports foreign + uploads nothing + masks the upload arrow to In sync. Wired `World.provision(direction=)` + `World.downloadController` direction gate.
- [x] 6.4 `openspec validate add-join-direction-mode --strict` → valid.
- [x] 6.5 On-device verified on the iPhone SE2 (iOS 26.5) via the ssh-mac dev-IPA build loop + headless `autoJoin` deeplink `direction` override. **Upload-only** (`direction=upload`, past cutoff): producer enabled ("re-registered"), download reconcile skipped ("reconcile skipped — download disabled"), 2 resources uploaded to `/files/devices/<id>/`. **Download-only** (`direction=download`): producer NOT enabled, reconcile ran (union read), byte partition stayed empty (uploaded nothing). The dev `direction` override is parsed + honored on the headless path.
