---
name: ssh-mac-build
description: >-
  Build, test, sign and package a SnapSync iOS build on a real macOS runner from
  Linux — the ssh-mac loop (dispatch a macos-26 GitHub runner, connect over an
  SSH-in-cloudflared tunnel, rsync, xcodebuild, hand re-sign, scp the IPA back).
  Use whenever the task needs a Mac, an Xcode build, an .xcarchive, an IPA, code
  signing, provisioning profiles for a build, or running the iOS simulator tests
  (iosSimulatorArm64Test) that cannot run on Linux.
---

# ssh-mac-build — the headless macOS build loop

You cannot build the Xcode project or run the iOS tests on Linux. `./gradlew
compileIosMainKotlinMetadata` is the **Linux-runnable proxy** — it compiles `iosMain`/`commonMain`
(and cinterop) without a Mac, so it catches iOS-only Kotlin breakage. Everything past that needs a
Mac.

For a fast **iterate** loop (not just one build), `.github/workflows/ssh-mac.yml` opens a long-lived
`macos-26` job with an SSH server the sandbox connects to, so you can `rsync → build → test →
dev-sign → scp back → install` many times against one **warm** runner instead of one CI run per
change. It is **dispatch-only, non-gating** dev infrastructure (no spec; rationale in the workflow
header). Public repo ⇒ the runner is **free**; the session self-closes after `stop_after` minutes
(default 90) or when you `touch /tmp/ssh-mac-stop`. This is an **operator/agent runbook, not CI
behavior**.

To install the resulting IPA on the phone, load `ios-device`. To refresh an expired provisioning
profile, load `asc-portal`.

## The auth model

You pass your **public** key at dispatch (safe — a pubkey is public and the private half never leaves
the sandbox); the runner authorizes exactly that key on its own sshd, fronted by a **cloudflared**
quick tunnel (relays encrypted TCP only). **No ASC key ever touches the box** — the runner holds only
the dev cert plus a pre-generated dev provisioning profile baked in as the
`DEV_PROVISIONING_PROFILE_BASE64` secret (a profile carries no private keys, so it is safe as a
secret). `cloudflared` is fetched to the scratchpad, **not** globally installed.

## The loop

```
S=/tmp/.../scratchpad                                          # session scratchpad
# 1. Ephemeral keypair (public half goes to CI; private half stays here)
ssh-keygen -q -t ed25519 -N '' -f "$S/ssh-mac"
# 2. cloudflared client (the ProxyCommand transport)
curl -sSL -o "$S/cloudflared" \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x "$S/cloudflared"
# 3. Dispatch and grab the run id
gh workflow run ssh-mac.yml -f ssh_pubkey="$(cat "$S/ssh-mac.pub")" -f stop_after=90
RID=$(gh run list -w ssh-mac.yml -L1 --json databaseId -q '.[0].databaseId')
# 4. Get the host from the ssh-mac-host ARTIFACT (logs are unreadable mid-run; v4 artifacts are)
until gh run download "$RID" -n ssh-mac-host -D "$S/host" 2>/dev/null; do sleep 5; done
HOST=$(cat "$S/host/ssh-mac-host.txt")                        # = <random>.trycloudflare.com
# 5. Connect (runner user is `runner`). Write the ssh invocation to a WRAPPER SCRIPT, not an alias:
#    rsync's `-e` re-splits what you hand it, and the ProxyCommand's own quotes do not survive that —
#    you get rsync's bare usage dump from the REMOTE side, which reads like a flag typo rather than a
#    quoting fault. A wrapper has no quoting to lose, and `-e "$S/sshmac.sh"` is then trivially correct.
cat > "$S/sshmac.sh" <<EOF
#!/bin/sh
exec ssh -i "$S/ssh-mac" -o StrictHostKeyChecking=no -o BatchMode=yes \\
  -o ProxyCommand="$S/cloudflared access ssh --hostname %h" "\$@"
EOF
chmod +x "$S/sshmac.sh"
sshmac() { "$S/sshmac.sh" runner@$HOST "$@"; }
# 6. Iterate. NB: $RUNNER_TEMP is UNSET in an ssh shell (it is a GH-Actions-step var) — write outputs
#    under $HOME, not $RUNNER_TEMP, or paths resolve to read-only "/".
rsync -a --delete --protocol=29 -e "$S/sshmac.sh" \
  --exclude .git --exclude build --exclude .gradle --exclude .kotlin ./ runner@$HOST:snapsync/
sshmac 'cd snapsync && ./gradlew iosSimulatorArm64Test'
```

⚠️ **`--protocol=29` is REQUIRED, and omitting it fails in a way that names nothing.** macOS 26 ships
**openrsync** (`openrsync: protocol version 29`, self-described as "rsync version 2.6.9 compatible"),
not GNU rsync — Apple replaced it. A modern local rsync (3.2.7 here) negotiates protocol 31 and sends
options openrsync does not accept, so the remote prints its **whole usage block** and the local end
reports:

```
rsync: connection unexpectedly closed (0 bytes received so far) [sender]
rsync error: error in rsync protocol data stream (code 12) at io.c(232) [sender=3.2.7]
```

Nothing in that says "different rsync implementation" — it reads as a bad flag, and the usage dump
invites you to go hunting through your own options. `-z` is one of the casualties, hence `-a` above
rather than `-az`. Measured 2026-08-25 on macos-26 / Xcode 26.6; check `rsync --version` on the runner
before assuming otherwise, since this is an image property and Apple may move again.

Do **not** wrap the `until gh run download` poll in `ch-bg`: that poll is the workspace genuinely
waiting on its own build, so it *should* read as busy (CLAUDE.md, *Agent harness limits*). `ch-bg` is
for long-lived processes that are not the work — a tunnel you leave up, a `tail -f`.

## 6a. Build an UNSIGNED archive

Compiles the Kotlin frameworks + assembles app+appex. The Xcode project is `CODE_SIGN_STYLE=Automatic`,
which needs `-allowProvisioningUpdates` + the Admin ASC key (absent here) — so a *signed* archive is
impossible on the box. Build unsigned, re-sign by hand (6b).

**BUILD DEBUG, NOT RELEASE.** `-configuration Debug` links `linkDebugFramework`, skipping the
Kotlin/Native LLVM optimizer that dominates a Release link — and it reruns FULLY on every relink, so it
costs you on every iterate, not just cold. Measured on the warm runner (macos-26, 3 cores, Xcode 26.5,
`~/.konan` warm), archive of a ONE-FILE Kotlin change: **Release 449 s vs Debug 57 s (~8×)**;
cold-from-empty-`build/`: Release 523 s vs Debug 348 s; no-op rebuild ~30 s either way. The dev/sideload
IPA needs no optimization, and the Debug archive is a complete installable bundle (arm64 app binary +
`BackgroundUploadExtension.appex` in `Extensions/`) — the 6b re-sign is config-agnostic, so ONLY this
`-configuration` line changes. Switch to Release only when you need an optimization-representative
build. Keep the cold cost paid once: never wipe `build/` or `.gradle` between iterates (the step-6
rsync already excludes them) and keep the Gradle daemon alive (no `--no-daemon`) — an incremental Debug
iterate is then ~1 min.

```
sshmac 'cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
          -destination "generic/platform=iOS" -archivePath "$HOME/artifacts/SnapSync.xcarchive" \
          CODE_SIGNING_ALLOWED=NO archive'
```

## 6b. Manually re-sign the archive INSIDE-OUT, then repackage the IPA

The entitlements come from the **REPO's own** `.entitlements` files, with the build variables expanded
— **NOT** from the profile.

**WHY not `xcodebuild -exportArchive`:** automatic-signing export does NOT reuse manually-installed
profiles without an ASC key (fails "No profiles for 'app.snapsync…' were found"); and the
`CODE_SIGNING_ALLOWED=NO` archive has EMPTY entitlements, so any export ships an IPA that aborts at
launch on the App-Group container ("client is not entitled").

⚠️ **A PROFILE IS A GRANT; ENTITLEMENTS ARE A CLAIM.** The profile says "you MAY use anything in
`<TEAM>.*`"; entitlements say "I AM this". Copying one into the other is a category error, and it is
silently wrong for every **WILDCARD** key an Apple DEV profile carries — of which there are two:

- `associated-domains: *` → the app claims every domain, therefore NONE. Every universal link dies
  silently (verified 2026-07-16).
- `keychain-access-groups: <TEAM>.*` → not a writable group name, so each process falls back to its
  OWN `application-identifier` group. The app and the upload extension then hold DIFFERENT device ids,
  both reads succeed, and the app re-imports every photo it uploaded (2026-07-20). SINCE
  device-identity started naming the group EXPLICITLY (`kSecAttrAccessGroup =
  <TEAM>.app.snapsync.shared`), the wildcard is WORSE than silent: an explicit-group query is not
  satisfied by a `<TEAM>.*` entitlement, so the read throws `errSecMissingEntitlement` (-34018) and the
  launch coroutine — see the app-scope error boundary in `app/ios/CLAUDE.md` — logs it rather than
  aborting, but the app is dead in the water (no device id). A hand-narrowed re-sign that kept the
  keychain wildcard did exactly this on 2026-07-21. **USE `build_ent` BELOW; never re-sign by narrowing
  the profile grant key-by-key.**

The keychain one is the worse of the two: it writes a real item to a real group, and the device id is
written once and never rewritten — so the mistake is frozen permanently, on a value whose loss is
unrecoverable. This is why we now GENERATE the claim instead of narrowing the grant key by key:
narrowing only ever fixes the wildcard you already know about (`associated-domains` was narrowed in
July; `keychain-access-groups` sat there unnarrowed the whole time and nobody connected the two).

⚠️ **A DONATED WILDCARD IS ONE WAY IN; A GARBAGE SUBSTITUTION IS THE OTHER.** `build_ent` is only as
good as the values it interpolates, and an EMPTY one lands in the same place by a different road:
`$(AppIdentifierPrefix)` → a bare `.`, so the binary claims `.app.snapsync.shared` and the app boots with
no device id, exactly as above. This is not hypothetical — it happened on 2026-08-25, because
`TEAM_ID`/`ASSOCIATED_DOMAIN` had moved into the generated `Deployment.xcconfig` and this step still
awked them out of `Config.xcconfig`, which matches nothing and yields the empty string in silence.
Neither existing check could see it: the wildcard guard tests for the ABSENCE of a leaked grant, and
`.app.snapsync.shared` contains no wildcard; `codesign -v` validates the signature, not the claim.
Hence the fail-closed checks below and the POSITIVE post-sign assertion beside the negative one — and
in the repo, a `:test:architecture` gate that no file reads a fragment-owned key out of
`Config.xcconfig` (capability `deployment-configuration`).

The profile-resolve supplied THREE things for free that the repo `.entitlements` do NOT carry —
`application-identifier`, `com.apple.developer.team-identifier`, and `get-task-allow`. The first is
MANDATORY: without it the install is refused ("Application is missing the application-identifier
entitlement", verified 2026-07-20). Add all three back. The two id keys are CONCRETE in the profile
(never wildcards), so extracting exactly them from the matched profile is safe — it is only the
wildcard keys that a grant must never donate to a claim.

```
sshmac 'bash -se' <<'SIGN'
set -e; cd "$HOME/artifacts"
SRC="$HOME/snapsync/iosApp"
PD="$HOME/Library/MobileDevice/Provisioning Profiles"
ID=$(security find-identity -v -p codesigning | awk '/Apple Development/{print $2; exit}')
APP="SnapSync.xcarchive/Products/Applications/SnapSync.app"
EXT="$APP/Extensions/BackgroundUploadExtension.appex"          # iOS 26 uses Extensions/, NOT PlugIns/
PB=/usr/libexec/PlistBuddy
# The GENERATED fragment, NOT Config.xcconfig. `TEAM_ID` and `ASSOCIATED_DOMAIN` moved here (capability
# `deployment-configuration`); Config.xcconfig now names them only in a header comment, so awking IT
# matches nothing and both variables come back EMPTY — the trap described above. xcodebuild's Gradle
# build phase runs the resolver, so this file exists by the time the archive does. The rendered domain
# already carries its `applinks:` prefix; nothing below prepends it.
CFG="$SRC/Configuration/Deployment.xcconfig"
TEAM=$(awk -F= '/^TEAM_ID/{gsub(/[ \t]/,"",$2);print $2}' "$CFG")
DOMAIN=$(awk -F= '/^ASSOCIATED_DOMAIN/{gsub(/[ \t]/,"",$2);print $2}' "$CFG")
# FAIL CLOSED. An empty value here is not a missing nicety — it signs a WRONG IDENTITY that every later
# check passes. If either fires, run `python3 scripts/resolve-deployment.py prod` and re-archive.
[ -n "$TEAM" ]   || { echo "TEAM_ID empty in $CFG — refusing to sign"; exit 1; }
[ -n "$DOMAIN" ] || { echo "ASSOCIATED_DOMAIN empty in $CFG — refusing to sign"; exit 1; }
build_ent() {                                                  # $1 = repo .entitlements, $2 = out, $3 = matched profile
  sed -e 's|\$(AppIdentifierPrefix)|'"$TEAM"'.|g' \
      -e 's|\$(ASSOCIATED_DOMAIN)|'"$DOMAIN"'|g' \
      -e 's|\$(APS_ENVIRONMENT)|development|g' "$1" > "$2"
  # The identity keys the profile-resolve used to supply. Concrete, never wildcards — safe to lift.
  local appid teamid
  appid=$(security cms -D -i "$3" | plutil -extract Entitlements.application-identifier raw -)
  teamid=$(security cms -D -i "$3" | plutil -extract Entitlements.com\\.apple\\.developer\\.team-identifier raw -)
  $PB -c "Add :application-identifier string $appid" "$2"      # MANDATORY — install fails without it
  $PB -c "Add :com.apple.developer.team-identifier string $teamid" "$2"
  $PB -c "Add :get-task-allow bool true" "$2"                  # dev-only; required to launch/debug
}
for p in "$PD"/*.mobileprovision; do                           # embed each profile + remember which target
  aid=$(security cms -D -i "$p" | plutil -extract Entitlements.application-identifier raw -)
  case "$aid" in
    *.app.snapsync.BackgroundUpload) EXTP="$p"; cp "$p" "$EXT/embedded.mobileprovision";;
    *.app.snapsync)                  APPP="$p"; cp "$p" "$APP/embedded.mobileprovision";;
  esac
done
build_ent "$SRC/iosApp/iosApp.entitlements" app.plist "$APPP"
build_ent "$SRC/BackgroundUploadExtension/BackgroundUploadExtension.entitlements" ext.plist "$EXTP"
# Nested frameworks first (deepest inside-out). The SPM `Sentry` product links STATICALLY into both
# binaries (nm-verified: classes defined in the app image, no load command) — but Xcode still embeds
# the binaryTarget's dynamic Sentry.framework in Frameworks/, unreferenced dead weight that must
# nonetheless be signed or the install is refused (measured 2026-07-21).
for fw in "$APP"/Frameworks/*.framework; do
  [ -d "$fw" ] && codesign -f -s "$ID" "$fw"
done
codesign -f -s "$ID" --entitlements ext.plist "$EXT"           # …then the extension (inside-out)…
codesign -f -s "$ID" --entitlements app.plist "$APP"           # …then the app
# THE GUARDS — two of them, asking OPPOSITE questions. Neither subsumes the other; keep both.
# (1) NEGATIVE — no wildcard may reach a signed binary. Key-agnostic ON PURPOSE: it catches whichever
#     wildcard key Apple adds next, which per-key narrowing by construction cannot.
# (2) POSITIVE — the claim carries the REAL identity. (1) is blind to this: an empty $TEAM yields
#     `.app.snapsync.shared`, which contains no wildcard and sails straight through, and `codesign -v`
#     passes too — the signature is perfectly valid, it just claims the wrong identity. Absence of a
#     wildcard is not presence of the right prefix. Checked on BOTH binaries: app and extension must
#     land in the SAME keychain group or they hold different device ids (the 2026-07-20 split above).
#     `grep -qF` because the `.` in `<TEAM>.app.snapsync.shared` is a regex metacharacter.
for b in "$EXT" "$APP"; do
  ENT=$(codesign -d --entitlements :- "$b" 2>/dev/null)
  if printf '%s' "$ENT" | grep -q '[*]'; then
    echo "WILDCARD LEAKED into $b — do not install this build:"
    printf '%s' "$ENT" | plutil -p -; exit 1
  fi
  if ! printf '%s' "$ENT" | grep -qF "$TEAM.app.snapsync.shared"; then
    echo "KEYCHAIN GROUP LACKS THE TEAM PREFIX ($TEAM) in $b — do not install this build:"
    printf '%s' "$ENT" | plutil -p -; exit 1
  fi
  # The app claims the associated domain; the extension declares none (it never handles URLs).
  if [ "$b" = "$APP" ] && ! printf '%s' "$ENT" | grep -qF "$DOMAIN"; then
    echo "ASSOCIATED DOMAIN ($DOMAIN) MISSING from $b — every universal link would open Safari:"
    printf '%s' "$ENT" | plutil -p -; exit 1
  fi
done
codesign -v "$EXT" && codesign -v "$APP"
rm -rf Payload && mkdir Payload && cp -R "$APP" Payload/ && zip -qry SnapSync.ipa Payload
SIGN
scp -o ProxyCommand=... runner@<HOST>:artifacts/SnapSync.ipa "$S/"
sshmac 'touch /tmp/ssh-mac-stop'                                            # end the session
```

Then install it — **SIGKILL the app first**; see `ios-device`.

The non-root sshd + `cloudflared access ssh` handshake were proven on 2026-07-01; the
**unsigned-archive + manual re-sign** path (replacing the earlier `-exportArchive` claim, which does not
reuse installed profiles without an ASC key) was proven on 2026-07-05 — a dev IPA built this way
installs and launches on the SE2.

## Pointing a build at a local backend

The upload host is **compile-time** (PhotoKit forces it), so this needs a rebuild. One generated
`Deployment.plist` is copied into **both** bundles, so one re-resolve covers the app and the extension.

🚫 **`BACKGROUND_UPLOAD_URL_BASE=` on the xcodebuild line does nothing.** It has not worked since the
device-facing values moved out of the xcconfig into that bundled resource (capability
`deployment-configuration`) — an `xcodebuild` build setting cannot substitute into a resource file. The
override is **accepted and ignored**, and the build silently bakes the *production* host instead, which
is the exact silent-misdirection failure that move existed to remove. Do not reach for it.

Retarget by **selecting the deployment**: write the rig's host into `deployments/local.json` and re-run
the resolver. That runs *after* cloudflared has minted the tunnel hostname, which is what the old
override was working around. The scheme is derived from the host — `http` for a loopback literal,
`https` for a tunnel — so you never state it.

```bash
H=$(cat api/.localdev/host)      # e.g. random-words.trycloudflare.com  (no scheme)
python3 - "$H" <<'EOF'
import json, pathlib, sys
p = pathlib.Path("deployments/local.json"); d = json.loads(p.read_text())
d["domain"] = sys.argv[1].replace("https://", "").replace("http://", "").rstrip("/")
p.write_text(json.dumps(d, indent=2) + "\n")
EOF
python3 scripts/resolve-deployment.py local     # renders Deployment.plist with that host
sshmac "cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
          -configuration Debug -destination 'generic/platform=iOS' \
          -archivePath \"\$HOME/artifacts/SnapSync.xcarchive\" \
          CODE_SIGNING_ALLOWED=NO archive"
# then the unchanged 6b re-sign + install steps above
```

⚠️ `deployments/local.json` is COMMITTED — the edit above is a working-tree change. Revert it
(`git checkout deployments/local.json`) before you commit anything, or a session's tunnel hostname
lands in the repo.

A quick tunnel's hostname is **random per session**, so the IPA is rebuilt per session (~1 min
incremental Debug). ⚠️ Crossing backends needs a **device reset** (`POST /device/reset` over the control
channel — the `SNAPSYNC_RESET_STATE` launch trigger is gone) in **both** directions or nothing uploads,
silently — load `local-backend` before doing this.

`ios.yml` DOES carry a `workflow_dispatch` now: it archives Release and delivers the branch to internal
TestFlight, which is the route to a phone with no cable. It does not replace this loop — it produces no
IPA you can sideload, and a TestFlight build carries no control channel — but it is the way to get a
DSN-carrying build onto a device (capability `ios-ci`).

## Provisioning profiles

Same one-time device prerequisites as installing a dev IPA (registered UDID + Developer Mode; see
`ios-device`). The `DEV_PROVISIONING_PROFILE_BASE64` secret is a **tar of both** the app
(`app.snapsync`, profile *SnapSync Dev Push*) and extension (`app.snapsync.BackgroundUpload`, *SnapSync
Ext Dev Push*) dev profiles — the re-sign step above signs both targets.

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
P="proton-env -- uvx --from codemagic-cli-tools app-store-connect"
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
