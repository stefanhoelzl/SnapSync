---
name: ssh-mac-build
description: >-
  Build and test SnapSync on a real macOS runner from Linux — open a session with
  the `ssh-runner` skill, rsync, xcodebuild an unsigned archive for the global
  `ios-device` skill to sign on Linux, point a build at a local backend, or run
  the iOS simulator tests (iosSimulatorArm64Test) that cannot run on Linux. Use
  whenever the task needs a Mac, an Xcode build, an .xcarchive, an IPA, or the
  SnapSync specifics of signing and provisioning profiles for a dev build.
---

# ssh-mac-build — the headless macOS build loop

You cannot build the Xcode project or run the iOS tests on Linux. `./gradlew
compileIosMainKotlinMetadata` is the **Linux-runnable proxy** — it compiles `iosMain`/`commonMain`
(and cinterop) without a Mac, so it catches iOS-only Kotlin breakage. Everything past that needs a
Mac.

**This skill owns the SnapSync half only** — the archive, the deployment, the properties. Three global
skills own the rest:

- **`ssh-runner`** owns the macOS box, configured by `.ssh-runner.yml` at the repo root: one warm
  runner, many iterations, instead of one CI run per change. Dev infrastructure —
  `workflow_dispatch`-only, no status check, gates nothing.
- **`ios-device`** owns the device loop — build on the runner, **sign on Linux**, install, launch —
  configured by `.ios-device.yml` at the repo root. The runner holds **no signing material**.
- **`asc-portal`** mints provisioning profiles.

To install the IPA, load the global `ios-device` skill, then `snapsync-device` for the SnapSync facts.
To drive the running app, load `rig-channel`.

## The session

The loop needs the global skill installed; nothing else raises if it is missing, so check first.

```bash
test -f ~/.claude/skills/ssh-runner/ssh-runner.ts || {
  echo "the ssh-runner skill is not installed — this loop cannot open a session without it"; exit 1; }

ID=$(node ~/.claude/skills/ssh-runner/ssh-runner.ts start | tail -1)   # id is on the LAST line
node ~/.claude/skills/ssh-runner/ssh-runner.ts sync $ID ./ :           # ':' = the checkout dir
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID '<command>'    # runs in the workspace
node ~/.claude/skills/ssh-runner/ssh-runner.ts stop $ID                # always stop
```

⚠️ **Write the path out in full, as above.** The global skill's docs abbreviate it to
`R=~/.claude/skills/ssh-runner/ssh-runner.ts; node $R start`, but `.claude/settings.json` grants
`Bash(node ~/.claude/skills/ssh-runner/ssh-runner.ts:*)` and the permission matcher sees the **raw
command string** — `$R` does not match it, so the shorthand prompts on every single call.

⚠️ **`:` anchors at the WORKSPACE, not at `$HOME`** — unlike the `scp` the old loop used, whose
relative paths were home-relative. So the loop archives into `artifacts/` *inside* the workspace and
`.ssh-runner.yml` excludes that directory (and `ios-device-out/`, the global recipe's staging
directory), which is also what stops the next push's `--delete` from removing the build you just
made. Measured 2026-09-11: pulling `:artifacts/…` while archiving to `$HOME/artifacts` fails with
`No such file or directory` naming a workspace path.

Everything the build needs is already in the job (see `.ssh-runner.yml`): JDK 25, Gradle and a warm
`~/.konan`. Read that file rather than re-deriving it; it carries the reasoning for each.

Do **not** wrap `start` in `ch bg`: it is the workspace genuinely waiting on its own build, so it
*should* read as busy (CLAUDE.md, *Agent harness limits*).

### Per-session properties go in the RUNNER's `~/.gradle`, never in the tree

`snapsync.rig` and `snapsync.deployment` are Gradle **properties**, and `GRADLE_USER_HOME`'s
`gradle.properties` outranks the project's — so set them there, outside the synced tree:

```bash
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID \
  'printf "snapsync.rig=true\n" >> ~/.gradle/gradle.properties'
```

🚫 **Never put `snapsync.rig=true` in the repo's tracked `gradle.properties`.** Nothing gates
against it, and one forgotten revert merged to `main` would link `:test:rig` — a control-channel
HTTP server — into every TestFlight and App Store build. The compile-time containment that spec
claims rests entirely on that property never being set in a committed file. `~/.gradle` is
uncommittable by construction, survives every rsync with no exclusion, and needs no cleanup: the
runner is gone at `stop`.

## 1. Build an UNSIGNED archive — `.ios-device.yml`

Run the global `ios-device` skill's build step (its step 3) unchanged: it runs the `build:` line from
`.ios-device.yml` on the runner, stages the `.app`, dumps each target's build settings, and pulls both
back to `build/ios-device/`. The build line does two things, in this order:

1. **Renders the deployment before `xcodebuild` loads the project.** `Deployment.xcconfig` is generated
   and gitignored, and `.ssh-runner.yml` excludes it from the sync so a stale local rendering cannot be
   pushed. Gradle's own re-resolve fires in the `embedAndSignAppleFrameworkForXcode` run-script phase,
   which is too late for an xcconfig. The line reads **`snapsync.deployment` from the runner's
   `~/.gradle/gradle.properties`** (default `prod`) and hands it to `scripts/resolve-deployment.py` —
   so the deployment is named ONCE, and the xcconfig and `Deployment.plist` can never come from two
   different deployments.
2. **Archives Debug with `CODE_SIGNING_ALLOWED=NO`.** The Xcode project is `CODE_SIGN_STYLE=Automatic`,
   which needs `-allowProvisioningUpdates` and the Admin ASC key, absent on the box by design. Signing
   happens afterwards, on Linux.

**BUILD DEBUG, NOT RELEASE.** `-configuration Debug` links `linkDebugFramework`, skipping the
Kotlin/Native LLVM optimizer that dominates a Release link — and it reruns FULLY on every relink, so it
costs you on every iterate, not just cold. Measured on the warm runner (macos-26, 3 cores, Xcode 26.5,
`~/.konan` warm), archive of a ONE-FILE Kotlin change: **Release 449 s vs Debug 57 s (~8×)**;
cold-from-empty-`build/`: Release 523 s vs Debug 348 s; no-op rebuild ~30 s either way. The dev/sideload
IPA needs no optimization, and the Debug archive is a complete installable bundle (arm64 app binary +
`BackgroundUploadExtension.appex` in `Extensions/`). Switch to Release (in `.ios-device.yml`: `build:`
and `configuration:` together) only when you need an optimization-representative build. Keep the cold
cost paid once: never wipe `build/` or `.gradle` between iterates (`.ssh-runner.yml` already excludes
them from the sync) and keep the Gradle daemon alive (no `--no-daemon`) — an incremental Debug iterate
is then ~1 min.

## 2. Sign and install — the global `ios-device` skill

Steps 4–5 of that skill: `sign` then `install`, both on Linux. There is no SnapSync signing script any
more. What SnapSync's history taught about signing is now enforced by `sign` itself, and is worth
knowing when it refuses:

⚠️ **A PROFILE IS A GRANT; ENTITLEMENTS ARE A CLAIM.** A dev profile *grants* wildcards
(`associated-domains: *`, `keychain-access-groups: <TEAM>.*`); a wildcard *claim* makes the app claim
every domain and therefore none (every universal link opens Safari), and makes the explicit-group
keychain read throw `errSecMissingEntitlement` (-34018) — the app then runs with no device id, a value
written once and never rewritten. `sign` generates each claim from the repo's own `.entitlements` and
refuses any wildcard in it.

⚠️ **An EMPTY interpolated value lands in the same place by a different road.** `$(AppIdentifierPrefix)`
→ a bare `.` claims `.app.snapsync.shared` (2026-08-25). `sign` expands each variable from that target's
real build settings and refuses one that is absent or empty, then refuses to write the IPA unless the
entitlements signed into each binary **equal** the claim.

## Pointing a build at a local backend

The upload host is **compile-time** (PhotoKit forces it), so this needs a rebuild. One generated
`Deployment.plist` is copied into **both** bundles, so one re-resolve covers the app and the extension.

🚫 **`BACKGROUND_UPLOAD_URL_BASE=` on the xcodebuild line does nothing.** It has not worked since the
device-facing values moved out of the xcconfig into that bundled resource (capability
`deployment-configuration`) — an `xcodebuild` build setting cannot substitute into a resource file. The
override is **accepted and ignored**, and the build silently bakes the *production* host instead. Do not
reach for it.

Retarget by **selecting the deployment**: write the rig's host into `deployments/local.json`, sync, and
name `local` in the runner's `~/.gradle` — once. The `.ios-device.yml` build line picks it up for both
the resolver and Gradle.

```bash
H=$(cat api/.localdev/host)      # e.g. random-words.trycloudflare.com  (no scheme)
python3 - "$H" <<'EOF'
import json, pathlib, sys
p = pathlib.Path("deployments/local.json"); d = json.loads(p.read_text())
d["domain"] = sys.argv[1].replace("https://", "").replace("http://", "").rstrip("/")
p.write_text(json.dumps(d, indent=2) + "\n")
EOF
node ~/.claude/skills/ssh-runner/ssh-runner.ts sync $ID ./ :
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID \
  'printf "snapsync.deployment=local\n" >> ~/.gradle/gradle.properties'
# then the unchanged build + sign + install steps above
```

The build line takes the **last** `snapsync.deployment=` line, so switching back is another append
(`snapsync.deployment=prod`), not an edit.

**Verify the bundle before you drive it** — one command, and it turns a silent misdirection into an
answer you can read:

```bash
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID \
  'plutil -p artifacts/SnapSync.xcarchive/Products/Applications/SnapSync.app/Deployment.plist'
# → "uploadBase" => "http://127.0.0.1:8080/api/v1"   ← your host, not snapsync.stho.net
```

On a running app the same fact is `GET /device/state` → `build.uploadBase` (load `rig-channel`).

⚠️ `deployments/local.json` is COMMITTED — the edit above is a working-tree change. Revert it
(`git checkout deployments/local.json`) before you commit anything, or a session's tunnel hostname
lands in the repo.

A quick tunnel's hostname is **random per session**, so the IPA is rebuilt per session (~1 min
incremental Debug). ⚠️ Crossing backends needs a **device reset** (`POST /device/reset` over the control
channel) in **both** directions or nothing uploads, silently — load `local-backend` before doing this.

`ios.yml` carries a `workflow_dispatch`: it archives Release and delivers the branch to internal
TestFlight, which is the route to a phone with no cable. It does not replace this loop — it produces no
IPA you can sideload, and a TestFlight build carries no control channel — but it is the way to get a
DSN-carrying build onto a device (capability `ios-ci`).

## Provisioning profiles

Same one-time device prerequisites as any dev install (registered UDID + Developer Mode; see the
global `ios-device` skill). SnapSync needs two `IOS_APP_DEVELOPMENT` profiles: the app (`app.snapsync`)
and the extension (`app.snapsync.BackgroundUpload`).

`sign` **fetches them itself** from App Store Connect (the `ASC_*` mappings in `.secrets.yaml`), caches
them in `~/.cache/ios-device/profiles/`, and picks, per bundle, the unexpired profile that lists the
connected device and the signing certificate and **grants every claimed entitlement**. There is no baked
profile tar and no GitHub secret to refresh.

Re-mint a profile (load `asc-portal`) when it expires (~yearly), when you register a new device, **or
when you enable a bundle-id capability** — that last one silently *invalidates* the affected profile
(verified 2026-07-16: enabling Associated Domains flipped *SnapSync Dev Push* to `INVALID` while the
extension's profile, whose bundle id gained nothing, stayed `ACTIVE`). That used to be the worst failure
here, because the IPA installed and merely lacked the capability. `sign` now refuses such a profile by
name ("does not grant …"), falls back from its cache to App Store Connect, and refuses again if the fresh
one is stale too — at which point re-minting is the fix.
