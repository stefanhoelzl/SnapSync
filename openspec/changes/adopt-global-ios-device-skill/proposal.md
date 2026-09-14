## Why

Every phone on this machine is shared by every project, and there is now a global `ios-device` skill
(`~/.claude/skills/ios-device`) that owns the lease, the guard, a Linux re-sign, and the install for all
of them. SnapSync still runs its **own** copy of each, and the two do not see each other:

- **Two locks.** SnapSync's lease writes `~/.snapsync-device.lock`; the global lease writes
  `~/.cache/ios-device/locks/<udid>.lock`. A SnapSync workspace and any other project's workspace can
  hold "the" phone at the same time — the concurrent-installer wedge the lease exists to prevent (~2 h
  lost on 2026-08-09). The global skill names this in its own traps: *"Not coordinated: SnapSync's
  `scripts/device-lease` until it migrates."*
- **Two guards on every Bash call.** The project hook and the global hook both run. The project one
  matches by substring, so it denies any command that merely *names* a device tool — including a
  `grep` over its own script.
- **Two skills called `ios-device`.** The repo skill and the global skill collide on the name; which one
  loads is harness precedence, not a choice anyone made.
- **Signing that needs baked secrets.** `scripts/dev-sign` runs on the Mac against a certificate
  imported from GitHub secrets and a tar of provisioning profiles that must be re-baked by hand whenever
  a profile expires, a device is registered, or a capability is enabled. The global `sign` runs on
  Linux, fetches profiles from App Store Connect, and never puts the certificate on a runner.

## What Changes

- **Adopt the global skill for all five pieces**: lease, guard, re-sign, install, build loop.
  - Delete `scripts/device-lease`, `scripts/device-guard`, `scripts/dev-sign`, and the project
    `PreToolUse` hook in `.claude/settings.json`.
  - Add `.ios-device.yml`, the global skill's per-project config. Its `build:` line reads
    `snapsync.deployment` from the runner's `~/.gradle/gradle.properties` (default `prod`), renders the
    deployment, and archives an unsigned Debug build — so the deployment is named **once**.
  - Map `APPLE_DEV_CERT` / `APPLE_DEV_CERT_PASSWORD` in `.secrets.yaml` for the Linux re-sign.
- **Rename the repo skill** `ios-device` → `snapsync-device` and slim it to SnapSync facts only (bundle
  id, the two logs, event-link and upload verification, the rig boundary). It opens by loading the
  global `ios-device`. CLAUDE.md's Runbooks pointer follows the rename.
- **Update the skills that described the old tooling**: `rig-channel` and `ios-simulator` (lease and
  guard), `ssh-mac-build` (the signing and profile-refresh sections).
- **Strip the runner's signing**: `.ssh-runner.yml` loses its keychain, certificate-import and
  profile-install steps and excludes `ios-device-out`; the generated workflow is regenerated. After merge,
  the `DEV_PROVISIONING_PROFILE_BASE64` GitHub secret is deleted. `SIGNING_DEV_CERT_*` stays — `ios.yml`
  uses it.
- **BREAKING (dev infrastructure only)**: the old lease path stops being honoured. A sibling workspace on
  a branch older than this change keeps using the old lock until it rebases.
- **Delete `DeploymentKeyProvenanceTest`** and its `:test:architecture` input set. No customer-facing
  behaviour changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `architecture-guards`: removes the requirement *"No reader is left behind on a moved deployment key"*.
  The reader it was written for — the re-sign awking `Deployment.xcconfig` — leaves the repository.
  The contract it protected, *a reader resolving an empty required value fails closed*, stays in
  `deployment-configuration` untouched, and the global `sign` meets it by expanding the target's real
  build settings and refusing any variable that expands to empty.

## Impact

**Deleted**: `scripts/device-lease`, `scripts/device-guard`, `scripts/dev-sign`;
`test/architecture/…/DeploymentKeyProvenanceTest.kt`; the `deploymentKeyReaderSurfaces` input set in
`test/architecture/build.gradle.kts`.

**Added**: `.ios-device.yml`; two `.secrets.yaml` mappings.

**Edited**: `.claude/settings.json` (hook removed); `.claude/skills/ios-device/` →
`.claude/skills/snapsync-device/`; `.claude/skills/{rig-channel,ios-simulator,ssh-mac-build}/SKILL.md`;
`CLAUDE.md` (Runbooks pointer); `.ssh-runner.yml` and the regenerated
`.github/workflows/ssh-runner-macos.yml`.

**Outside the repo, after merge**: delete the `DEV_PROVISIONING_PROFILE_BASE64` secret; remove the
"Not coordinated: SnapSync" line from the global skill.

**Explicitly not touched**: the app, the extension, every shipped build, `ios.yml` and its signing,
`scripts/sim-sign`, and the simulator loop. `RunbookSkillsTest` needs no change — the pointer still
resolves in-repo.

**Dependencies**: the global `ios-device` skill (rcodesign 0.29.0, pinned and checksum-verified by the
skill) and `secrets-env` on the operator's machine. Neither reaches CI.
