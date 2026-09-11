---
name: ssh-mac-build
description: >-
  Build, test, sign and package a SnapSync iOS build on a real macOS runner from
  Linux — open a session with the `ssh-runner` skill, rsync, xcodebuild an
  unsigned archive, re-sign it by hand, and pull the IPA back. Use whenever the
  task needs a Mac, an Xcode build, an .xcarchive, an IPA, code signing,
  provisioning profiles for a build, or running the iOS simulator tests
  (iosSimulatorArm64Test) that cannot run on Linux.
---

# ssh-mac-build — the headless macOS build loop

You cannot build the Xcode project or run the iOS tests on Linux. `./gradlew
compileIosMainKotlinMetadata` is the **Linux-runnable proxy** — it compiles `iosMain`/`commonMain`
(and cinterop) without a Mac, so it catches iOS-only Kotlin breakage. Everything past that needs a
Mac.

**This skill owns the SnapSync half only** — the archive, the re-sign, the deployment, the
profiles. The macOS box itself belongs to the global **`ssh-runner`** skill, configured by
`.ssh-runner.yml` at the repo root: one warm runner, many iterations, instead of one CI run per
change. Dev infrastructure — `workflow_dispatch`-only, no status check, gates nothing.

To install the resulting IPA on the phone, load `ios-device`. To drive the running app, load
`rig-channel`. To refresh an expired provisioning profile, load `asc-portal`.

## The session

The loop needs the global skill installed; nothing else raises if it is missing, so check first.

```bash
test -f ~/.claude/skills/ssh-runner/ssh-runner.ts || {
  echo "the ssh-runner skill is not installed — this loop cannot open a session without it"; exit 1; }

ID=$(node ~/.claude/skills/ssh-runner/ssh-runner.ts start | tail -1)   # id is on the LAST line
node ~/.claude/skills/ssh-runner/ssh-runner.ts sync $ID ./ :           # ':' = the checkout dir
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID '<command>'    # runs in the workspace
node ~/.claude/skills/ssh-runner/ssh-runner.ts sync $ID :artifacts/SnapSync.ipa ./
node ~/.claude/skills/ssh-runner/ssh-runner.ts stop $ID                # always stop
```

⚠️ **Write the path out in full, as above.** The global skill's docs abbreviate it to
`R=~/.claude/skills/ssh-runner/ssh-runner.ts; node $R start`, but `.claude/settings.json` grants
`Bash(node ~/.claude/skills/ssh-runner/ssh-runner.ts:*)` and the permission matcher sees the **raw
command string** — `$R` does not match it, so the shorthand prompts on every single call.

⚠️ **`:` anchors at the WORKSPACE, not at `$HOME`** — unlike the `scp` the old loop used, whose
relative paths were home-relative. So the loop archives into `artifacts/` *inside* the workspace and
`.ssh-runner.yml` excludes that directory, which is also what stops the next push's `--delete` from
removing the build you just made. Measured 2026-09-11: pulling `:artifacts/…` while archiving to
`$HOME/artifacts` fails with `No such file or directory` naming a workspace path.

Everything the build needs is already in the job (see `.ssh-runner.yml`): JDK 25, Gradle, a warm
`~/.konan`, the Apple Development certificate, and both dev provisioning profiles. Read that file
rather than re-deriving it; it carries the reasoning for each.

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

### Always re-render the deployment on the runner, before `xcodebuild`

```bash
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID 'python3 scripts/resolve-deployment.py prod --quiet'
```

`Deployment.xcconfig` is generated and gitignored, and `.ssh-runner.yml` excludes it from the sync
so a stale local rendering cannot be pushed. It must exist **before `xcodebuild` LOADS the
project** — Gradle's own re-resolve fires in the `embedAndSignAppleFrameworkForXcode` run-script
phase, which is too late for an xcconfig. One line, and the ordering question disappears in both
the prod and the local case.

## 1. Build an UNSIGNED archive

Compiles the Kotlin frameworks + assembles app+appex. The Xcode project is `CODE_SIGN_STYLE=Automatic`,
which needs `-allowProvisioningUpdates` + the Admin ASC key (absent here by design) — so a *signed*
archive is impossible on the box. Build unsigned, re-sign by hand (step 2).

**BUILD DEBUG, NOT RELEASE.** `-configuration Debug` links `linkDebugFramework`, skipping the
Kotlin/Native LLVM optimizer that dominates a Release link — and it reruns FULLY on every relink, so it
costs you on every iterate, not just cold. Measured on the warm runner (macos-26, 3 cores, Xcode 26.5,
`~/.konan` warm), archive of a ONE-FILE Kotlin change: **Release 449 s vs Debug 57 s (~8×)**;
cold-from-empty-`build/`: Release 523 s vs Debug 348 s; no-op rebuild ~30 s either way. The dev/sideload
IPA needs no optimization, and the Debug archive is a complete installable bundle (arm64 app binary +
`BackgroundUploadExtension.appex` in `Extensions/`) — step 2 is config-agnostic, so ONLY this
`-configuration` line changes. Switch to Release only when you need an optimization-representative
build. Keep the cold cost paid once: never wipe `build/` or `.gradle` between iterates (`.ssh-runner.yml`
already excludes them from the sync) and keep the Gradle daemon alive (no `--no-daemon`) — an
incremental Debug iterate is then ~1 min.

```bash
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID \
  'xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
     -destination "generic/platform=iOS" -archivePath artifacts/SnapSync.xcarchive \
     CODE_SIGNING_ALLOWED=NO archive'
```

## 2. Re-sign and package — `scripts/dev-sign`

```bash
node ~/.claude/skills/ssh-runner/ssh-runner.ts exec $ID 'bash scripts/dev-sign artifacts/SnapSync.xcarchive'
node ~/.claude/skills/ssh-runner/ssh-runner.ts sync $ID :artifacts/SnapSync.ipa ./
node ~/.claude/skills/ssh-runner/ssh-runner.ts stop $ID
```

The script signs inside-out (frameworks → extension → app), embeds both profiles, and writes
`SnapSync.ipa` beside the archive. **Its header carries the full rationale** — read it before
changing anything in it. The two things worth knowing from out here:

⚠️ **A PROFILE IS A GRANT; ENTITLEMENTS ARE A CLAIM.** The profile says "you MAY use anything in
`<TEAM>.*`"; entitlements say "I AM this". Copying one into the other is a category error, and it is
silently wrong for every **wildcard** key an Apple dev profile carries. `associated-domains: *` makes
the app claim every domain and therefore none, killing universal links. `keychain-access-groups:
<TEAM>.*` is worse: since device-identity names the group explicitly, the read throws
`errSecMissingEntitlement` (-34018) and the app runs with no device id — a value written once and
never rewritten, so the mistake freezes permanently on the device. This is why the script GENERATES
the claim from the repo's own `.entitlements` rather than narrowing the grant key by key: narrowing
only ever fixes the wildcard you already know about.

⚠️ **An EMPTY interpolated value lands in the same place by a different road.** `$(AppIdentifierPrefix)`
→ a bare `.`, so the binary claims `.app.snapsync.shared` and boots with no device id — and no existing
check sees it, because the wildcard guard tests for a leaked grant and `.app.snapsync.shared` contains
no wildcard, while `codesign -v` validates the signature rather than the claim. Hence the script's
fail-closed checks and its POSITIVE post-sign assertion beside the negative one, and, in the repo, a
`:test:architecture` gate that no file reads a fragment-owned key out of `Config.xcconfig` (capability
`deployment-configuration`).

Then install it — **SIGKILL the app first**; see `ios-device`.

## Pointing a build at a local backend

The upload host is **compile-time** (PhotoKit forces it), so this needs a rebuild. One generated
`Deployment.plist` is copied into **both** bundles, so one re-resolve covers the app and the extension.

🚫 **`BACKGROUND_UPLOAD_URL_BASE=` on the xcodebuild line does nothing.** It has not worked since the
device-facing values moved out of the xcconfig into that bundled resource (capability
`deployment-configuration`) — an `xcodebuild` build setting cannot substitute into a resource file. The
override is **accepted and ignored**, and the build silently bakes the *production* host instead. Do not
reach for it.

Retarget by **selecting the deployment**: write the rig's host into `deployments/local.json`, then name
`local` in both places on the runner.

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
  'printf "snapsync.deployment=local\n" >> ~/.gradle/gradle.properties
   python3 scripts/resolve-deployment.py local --quiet'
# then the unchanged archive + dev-sign steps above
```

Both halves are needed and for different reasons: the **property** is what Gradle's own re-resolve
obeys during the build, and the **manual run** is what puts `Deployment.xcconfig` on disk before
`xcodebuild` evaluates it. If the two ever named different deployments you would get an xcconfig from
one and a `Deployment.plist` from the other. Naming `local` once in a single `exec` keeps them
together.

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

Same one-time device prerequisites as installing a dev IPA (registered UDID + Developer Mode; see
`ios-device`). The `DEV_PROVISIONING_PROFILE_BASE64` secret is a **tar of both** the app
(`app.snapsync`, profile *SnapSync Dev Push*) and extension (`app.snapsync.BackgroundUpload`, *SnapSync
Ext Dev Push*) dev profiles — `scripts/dev-sign` signs both targets, so both must be present.

Refresh it when they expire (~yearly), when you register a new device, **or when you enable a bundle-id
capability** — that last one silently *invalidates* the affected profile (verified 2026-07-16: enabling
Associated Domains flipped *SnapSync Dev Push* to `INVALID` while the extension's profile, whose bundle
id gained nothing, stayed `ACTIVE`). A stale profile is the worst kind of failure here: the re-sign
resolves entitlements **out of the repo**, so the IPA installs and launches fine and merely lacks the
capability — no error, no log line.

Refreshing needs **no Mac and no build** — mint and download both profiles over the ASC API from Linux
(load `asc-portal` for the credential bridge and `$A`), then tar them **flat** (the workflow globs
`$WORK/*.mobileprovision` and installs each by its embedded UUID, so filenames are free but nesting
breaks it):

```
P="secrets-env -- uvx --from codemagic-cli-tools app-store-connect"
$P profiles list $A --json                        # find the INVALID one + note cert/device ids
$P profiles delete <INVALID_PROFILE_ID> $A        # Apple rejects a duplicate name; delete first
$P profiles create <BUNDLE_RESOURCE_ID> $A --certificate-ids <CERT> --device-ids <DEVICE> \
     --type IOS_APP_DEVELOPMENT --name "SnapSync Dev Push" --save
$P profiles get <EXT_PROFILE_ID> $A --save        # the extension's, still ACTIVE — grab it as-is
# both land in ~/Library/Developer/Xcode/UserData/Provisioning Profiles/
tar -cf p.tar -C <dir> app.mobileprovision ext.mobileprovision   # FLAT
base64 -w0 p.tar | gh secret set DEV_PROVISIONING_PROFILE_BASE64
```

Verify before shipping — decode each and confirm the app's carries what you added and the extension's
does not: `openssl smime -inform DER -verify -noverify -in <p>.mobileprovision` (works on Linux; no
`security cms` needed).
