## 1. Per-project config for the global skill

- [x] 1.1 Add `.ios-device.yml` at the repo root. Keys: `runner-profile: macos`, `archive: artifacts/SnapSync.xcarchive`, `project: iosApp/iosApp.xcodeproj`, `configuration: Debug`. `build:` is one line: read `snapsync.deployment` from `~/.gradle/gradle.properties` (default `prod`), run `python3 scripts/resolve-deployment.py "$D" --quiet`, then run `xcodebuild … -configuration Debug -destination generic/platform=iOS -archivePath artifacts/SnapSync.xcarchive CODE_SIGNING_ALLOWED=NO archive`. Flat `key: value` lines, no trailing comments.
- [x] 1.2 Confirm `.secrets.yaml` maps `APPLE_DEV_CERT` and `APPLE_DEV_CERT_PASSWORD` (already added by the operator), and that `secrets-env` resolves both: check each is non-empty, never print either.

## 2. Runner

- [x] 2.1 In `.ssh-runner.yml`, remove the ephemeral-keychain, certificate-import and profile-install steps and the comments that justify them. Add `ios-device-out` to `sync.exclude`. Update the header comment's loop description from "dev-sign" to the global sign.
- [x] 2.2 Regenerate `.github/workflows/ssh-runner-macos.yml` with the `ssh-runner` skill's `init`. Never hand-edit it. Confirm no `SIGNING_DEV_CERT_*` or `DEV_PROVISIONING_PROFILE_BASE64` reference remains in either file.

## 3. Skills and CLAUDE.md

- [x] 3.1 Rename `.claude/skills/ios-device/` to `.claude/skills/snapsync-device/`, with frontmatter `name: snapsync-device` and a description that says it layers on the global `ios-device`.
- [x] 3.2 Slim `snapsync-device`:
  - Remove the lease, "Reaching the device", timeout table, restart and install sections. Remove the `pkill usbmux` trap; the global skill carries it.
  - Keep and tighten: bundle id `app.snapsync`, reading the app log and the extension log via the rig, the rig boundary, event-link verification, the upload landing check, the headless per-build loop.
  - Open with "load the global `ios-device` skill first".
- [x] 3.3 In `CLAUDE.md`'s Runbooks block, change the pointer to ``load **`snapsync-device`**``. Replace the `scripts/device-guard` mention with the global lease and guard, naming the global `ios-device` in prose only. Update the `scripts/sim-sign` line only if it names the old guard.
- [x] 3.4 In `rig-channel/SKILL.md`, point "Take the device lease first" at `ch bg ~/.claude/skills/ios-device/lease "<why>"` and the global guard. Point the "load `ios-device`" references at `snapsync-device` plus the global skill.
- [x] 3.5 In `ios-simulator/SKILL.md`, reword "`scripts/device-guard` does not fence `xcrun`/`simctl`" to name the global guard.
- [x] 3.6 In `ssh-mac-build/SKILL.md`:
  - Replace "2. Re-sign and package — `scripts/dev-sign`" with a pointer to the global loop (build settings dump, `sign`, `install`).
  - Replace "Provisioning profiles" with: profiles are fetched by `sign` from App Store Connect; mint or re-mint them with `asc-portal`.
  - Keep the Debug-not-Release, `~/.gradle` property, and local-backend sections, adjusted to the `.ios-device.yml` build line. The deployment is named once, in `~/.gradle`.
- [x] 3.7 Sweep for stale references outside `openspec/changes/archive/`: `grep -rn "device-lease\|device-guard\|dev-sign\|snapsync-device.lock\|DEV_PROVISIONING_PROFILE"`. Fix or account for each hit. The `test/architecture/build.gradle.kts` comment naming the `ios-device` launch-trigger index is one.

## 4. Remove SnapSync's own device tooling

- [x] 4.1 Delete `scripts/device-lease`, `scripts/device-guard` and `scripts/dev-sign`.
- [x] 4.2 Remove the `PreToolUse` hook that runs `scripts/device-guard` from `.claude/settings.json`. Keep the `USBMUXD_SOCKET_ADDRESS` env entry and the permissions.

## 5. Retire the provenance guard

- [x] 5.1 Delete `test/architecture/src/test/kotlin/app/snapsync/architecture/DeploymentKeyProvenanceTest.kt`.
- [x] 5.2 Remove the `deploymentKeyReaderSurfaces` `inputs.files(…)` block and its comment from `test/architecture/build.gradle.kts`. Keep `iosApp/Configuration/Config.xcconfig` in `guardedSources` only if another guard still reads it; check `EventLinkDomainTest` and `RuntimeIdentityTest`. (Both still read it, so it stays.)
- [x] 5.3 Grep the tree for `DeploymentKeyProvenanceTest` and `deploymentKeyReaderSurfaces`; no reference may remain outside `openspec/changes/`.

## 6. Local gates

- [x] 6.1 `./gradlew build` passes, including `:test:architecture` (`RunbookSkillsTest` resolves `snapsync-device`).
- [x] 6.2 `./gradlew architectureDiagrams` leaves `architecture/` unchanged, or its output is committed.
- [x] 6.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate adopt-global-ios-device-skill --strict` pass.

## 7. Device verification (SE2), before merge

- [x] 7.1 Take the global lease: `ch bg ~/.claude/skills/ios-device/lease "<why>"`. Confirm the sidebar shows `ios-device` and no project hook fires.
- [x] 7.2 Open a runner session, set `snapsync.rig=true` in the runner's `~/.gradle/gradle.properties`, and run the global recipe's build and per-target settings dump. Pull `build/ios-device/`.
- [x] 7.3 `secrets-env -- ~/.claude/skills/ios-device/sign --app build/ios-device/app --settings build/ios-device/settings --out build/ios-device/app.ipa` succeeds. Record the profile names and expiry dates it reports.
- [x] 7.4 `~/.claude/skills/ios-device/install build/ios-device/app.ipa` succeeds, then launch the app.
- [x] 7.5 Over the rig channel, `GET /device/state` reports a device id. That proves the keychain group `<TEAM>.app.snapsync.shared` is claimed by both bundles. (`/device/state` carries no device-id field. Measured instead from both processes' logs after the install: app and extension each `read` the same id `DD92FAC9-…`, with no -34018.)
- [x] 7.6 Join a fresh event created on this device, seed a photo, and confirm its object lands in the backend storage zone.
- [x] 7.7 Stop the runner, kill the lease shell, and confirm the lock file under `~/.cache/ios-device/locks/` is gone.

## 8. Ship and follow-through

- [x] 8.1 Open the PR with label `internal`. Its body states the uncoordinated window for sibling workspaces on stale branches; `/ship` it. (Ticked at archive time: the archive and spec sync ride along with this same `/ship`, which follows immediately.)
- [x] 8.2 `gh secret delete DEV_PROVISIONING_PROFILE_BASE64` (done before merge, at the operator's request).
- [x] 8.3 Remove the "Not coordinated: SnapSync's `scripts/device-lease`" trap from `~/.claude/skills/ios-device/SKILL.md` and commit it in the skills repo (done before merge, at the operator's request: `a675b0c`).
