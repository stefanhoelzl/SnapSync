# CLAUDE.md

SnapSync — an iOS app for **sharing photos from an event** (Kotlin Multiplatform + Compose), shipped
via TestFlight. Join an event by scanning its QR, and your photos taken since a per-device
**capture-date cutoff** are shared to it while everyone else's arrive in your library. The JVM desktop
app is test equipment, not a product.

> It began as a *personal one-way photo backup*. Defaults inherited from that era are dangerous here:
> what was "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's
> event". A membership's cutoff is therefore **required**, never absent.

What a member contributes is decided by **one** policy at **one** place (capability
`photo-selection-policy`, enforced in `UploadCycle`'s resource selection): the cutoff bounds *when* a photo
was taken; the **origin exclusions** bound *what it is* — screenshots, screen recordings, GIFs,
sub-floor-resolution received media, and members of a denylisted album (WhatsApp, Telegram, …) never enter
an event. PhotoKit exposes **no** camera-origin flag on any iOS through 26, so the policy can only
*subtract* known non-captures and **admits on doubt**: a stray uploaded meme is harmless and visible, while
an event photo that silently fails to upload is invisible and unfixable. The same policy gates the byte
upload, the device manifest (or an excluded photo leaks into the event union), **and** the status total `N`
(or the screen pegs below 100% forever).

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor.
(Two upload tiers per OS version: OS-driven PhotoKit on iOS ≥26.1, app-driven background `URLSession`
on iOS 18–26.0 — see the `ios-photokit-upload` / `ios-url-session-upload` specs.)
(`gradle/libs.versions.toml` is the source of truth for versions.)

## Read first

There is no narrative design doc. Two OpenSpec trees carry the whole design:

- **`openspec/specs/<capability>/spec.md`** — the **contract of record**. One spec per capability, each
  a `## Purpose` (what it is for, and why) plus `## Requirements` (SHALL/WHEN/THEN). A spec is
  authoritative for its own contract; it never defers that to a doc outside `openspec/`.
- **`openspec/changes/archive/<id>/`** — the **decision record**. Each archived change holds
  `proposal.md` (what & why) and `design.md` (`## Context`, `## Goals / Non-Goals`, `## Decisions`) —
  this is where rationale, rejected alternatives, and trade-offs live. Specs cite theirs as
  `Decision record: changes/archive/<id>`.

Read the spec for the capability you are changing, then its decision record before altering behavior.

## Build & test

- `./gradlew build` — the canonical check (compiles all targets + runs JVM tests). **No display
  needed**: the Compose Desktop UI tests (`:domain:ui:jvmTest`) render offscreen under
  `-Djava.awt.headless=true` (set on that task in `domain/ui/build.gradle.kts`), so no X server /
  Xvfb is required. Only the two harness run tasks — `:app:desktop:ui:run` (forge) and
  `:app:desktop:run` (full-stack world, below) — open a real window and need a display.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

## Test UI (review/exercise every UI state)

`./gradlew :app:desktop:ui:run` launches the forge harness (module `:app:desktop:ui`): the real
`:domain:ui` status screen inside a phone-sized frame on the left, and a **control panel** on the right
(raw Material 3 — it is test equipment, never `App*`). The panel **forges any display state** — permission presets,
sync-state presets, and the engine console — so you can review and test all UI states without a
device. See the `desktop-test-harness` spec.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (module `:app:desktop`): the same
real status screen on the left — but its counts **emerge** from `:test:world`'s real `ListingSyncStatusSource`
(never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
**Invoke extension** (one `process()`-shaped cycle + download reconcile), the gallery/backend, the
upload-job queue and downloads, failure levers, and an engine-console footer. The operator plays the OS
(nothing auto-runs). See the `full-stack-harness` spec.

## On-device iOS (agent-driveable over USB)

The iOS PhotoKit upload extension is **physical-device-only** (no simulator support; spec
`ios-photokit-upload`). It ships against the **deprecated iOS 26.1** `PHBackgroundResourceUploadExtension`
— the only protocol runnable on current GM devices — and
its *upload trigger* (`process()`) is OS-scheduled — it cannot be forced. But everything around
it — install, **launch**, **screenshot**, event-subscribe, logs — is **scriptable headless over
USB, no root and no Mac**. Reach a connected iPhone through the host's usbmuxd — **this is specific
to the codehydra sandbox** (the host socket is bridged at `/run/host/run/usbmuxd`).

Lockdown-level tools (no developer tunnel needed):

```
export USBMUXD_SOCKET_ADDRESS=UNIX:/run/host/run/usbmuxd
idevice_id -l          # list connected devices
ideviceinfo            # device details (UDID, model, iOS version)
idevicesyslog          # live device log — watch the app/extension at runtime
idevicecrashreport .   # pull crash reports
```

**Developer services — the `--userspace` unlock.** Launch, screenshot, and the rest of the DVT
surface need iOS 17+'s RemoteXPC tunnel + a mounted DeveloperDiskImage, which normally want root
and **hang over the usbmux bridge** (`idevicescreenshot`/`idevicedebug` fail here for this reason).
`pymobiledevice3 --userspace` builds the tunnel **in-process — no root** — and auto-mounts the DDI;
it needs **Python ≥3.14**, so pin it via `uvx --python 3.14`. Use the **bare** socket path (no
`UNIX:` prefix). Verified on the SE2 (iOS 26.5):

```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
P="uvx --python 3.14 pymobiledevice3"
$P developer dvt launch app.snapsync --userspace                 # launch (prints the pid)
$P developer dvt screenshot shot.png --userspace                 # real screen capture (auto-mounts the DDI)
$P developer dvt launch app.snapsync \                           # subscribe to an event headlessly:
  --env SNAPSYNC_DEEPLINK="snapsync://config?v=3&d=<base64url({\"eventId\":\"<uuid>\"})>" --userspace
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log   # pull the file logger (re-provision line, etc.)
```

**Seeding a large photo library.** `SNAPSYNC_SEED_PHOTOS=<n>` is a second dev/test launch-env trigger
(`app/ios/.../DevPhotoSeeder.kt`): on launch the app creates `<n>` synthetic `PHAsset`s dated from
2001-01-01 forward, one minute apart, so the capture-date-bounded walk can be exercised against a large
library on device. ~85 s for 4000 assets on an SE2. Inert in production for the same reason as
`SNAPSYNC_DEEPLINK` (a launch env var is only injectable via a developer launch).
**It writes to the real photo library** — deleting the assets again needs taps (`deleteAssets` always
raises a system confirmation), which is why they are parked in one year of the Photos timeline. Use it on
a dev device only.
```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_PHOTOS=4000 --userspace
```
Why it matters: the walk's cost is one synchronous PhotoKit XPC round-trip **per asset**
(`assetResourcesForAsset`, ~110 ms each on an SE2), so ~90 assets exhaust the 10 s scene-update watchdog.
A one-photo dev device cannot distinguish a bounded fetch from an unbounded one.

**These seeds never upload, by design** — they are dated 2001, i.e. before any plausible cutoff. (They are
also 64×64, three orders of magnitude below the selection policy's 3 MP image floor, so they are doubly out
of scope.) They exercise the *walk*, not the upload.

**Seeding for the selection policy.** `SNAPSYNC_SEED_POLICY=<n>` seeds `n` assets dated **an hour ahead**
— past any cutoff an event created today can carry (the cutoff is clamped to `max(chosen, startsAt)`) —
**alternating above and below the 3 MP floor**. It exists because a dev device may hold *no real photos at
all*, and without an asset the policy admits, a run cannot tell "the policy correctly excluded everything"
from "the fetch predicate silently returned nothing" — and the wrong predicate form returns **zero rows
without raising**, so that is precisely the confusion that matters. One launch answers everything: the walk
returns assets, exactly the below-floor half is `origin-excluded`, and only the rest uploads.
```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_POLICY=20 \
  --env SNAPSYNC_DEEPLINK="snapsync://config?v=3&d=<…fresh event…>" --userspace
```
Read the outcome from the two log lines the policy emits **before any HTTP call** — so an attestation `401`
can never be mistaken for an exclusion:
- app: `gallery: enumerated N resource(s) … (M origin-excluded) → N=…`
- extension: `origin policy dropped N resource(s)`

`SNAPSYNC_DEEPLINK` is a **dev/test trigger** (capability `ios-app-shell`): on launch the app
forwards it through the same path as a scanned QR, (re)provisioning the event. It is read **once per
process** and is inert in production (a launch env var is only injectable via a developer launch).
**Note:** re-provision no longer forces a fresh whole-library upload — it **reconciles against storage**
(`event-rejoin-reconciliation` seeds already-stored photos as `COMPLETED` before any upload job is
created), so a relaunch against an event that already has objects uploads **nothing new**. The reconcile
runs inside the shared `UploadCycle` and is a **required** constructor parameter, so it holds on **both**
tiers. (It did not always: it was wired per-composition-root and the iOS 18–26.0 tier shipped without one,
so a switch / leave-then-rejoin / reinstall re-uploaded the whole post-cutoff library. Fixed in
`changes/archive/…-fix-app-driven-upload-lifecycle`.) To observe real uploads in the dev loop, point at a
**fresh event id** (or clear the event's objects in the bunny zone) so the reconcile finds nothing to seed.

**Restarting the app (black-screen trap).** `dvt launch --kill-existing` — and `dvt kill`/`pkill` —
only send **SIGTERM**, which SnapSync ignores; a relaunch then layers a new instance on the
still-alive old one and the app sticks on a **black launch screen** (status bar visible, content
black). To truly restart: `dvt signal <pid> 9` (SIGKILL) **then** `dvt launch` (verified recovery).
Take the screenshot promptly after a single launch; avoid rapid relaunch cycles.

**The headless per-build loop:** CI builds the dev IPA → `apps install` → `dvt launch --env
SNAPSYNC_DEEPLINK=…` (use a **fresh event id**, per the note above, or the reconcile will seed
already-stored photos and nothing uploads) → the OS invokes the upload extension on its own cadence →
confirm the objects landed in the backend's bunny storage zone (see *Verify real uploads* below; the
`dvt screenshot` status counts are informational, not the authoritative landing check). **Still
gated:** taps / UI gestures need a signed **WebDriverAgent** (`developer wda`), and `process()`
**timing** is OS-owned — a re-provision reliably triggers an invocation but you cannot force *when* it
runs.

### `main` is the public alpha channel

Every merge to `main` reaches **public** TestFlight testers, automatically and **silently** (capability
`ios-testflight-delivery`). `ios.yml` runs `ios-build` + `ios-test` (the merge gates) → `ios-deliver`
(export + upload) → **`ios-promote`**, which puts the build in the **`alpha` external group**, whose
public link — <https://testflight.apple.com/join/pvqgV7Uz> — anyone may tap. **Uploading is not
distributing:** without `ios-promote` a build reaches only the internal `development` group.

- **Testers are never notified.** `ios-promote` sets `autoNotifyEnabled=false` on every build; testers
  ride `main` via TestFlight auto-update. The suppression **must** precede the group assignment (the
  notification fires on group availability) — do not reorder those steps.
- **Every** `main` build is promoted, unfiltered. Docs-only and backend-only merges therefore ship a
  binary-identical build. Deliberate: any filter risks a real fix *silently* never reaching testers,
  which is worse than noise. (And a path filter on the trigger would freeze merges — `ios-build` /
  `ios-test` are required checks, and a skipped required check is never posted.)
- **Beta App Review is not a gate** — a build on an already-approved `MARKETING_VERSION` auto-approves
  instantly, and a build can join the group while still `WAITING_FOR_REVIEW`.
- ⚠️ **The `MARKETING_VERSION` trap — avoided by design; do not re-introduce it.** The fallback is
  pinned at `0.1.0` in **`Config.xcconfig`** (inherited by both targets; no target-level entry in
  `project.pbxproj`), and **`main` is never bumped** — real store versions are injected per release by
  the tag channel below. *Were* you to bump the committed fallback, it would force a **real**
  first-of-version Beta App Review taking **hours to days**, during which each merge expires its
  predecessor's submission (`--expire-build-submitted-for-review`, newest wins), so **nothing reaches
  testers and nothing goes red** — a green pipeline delivering nothing. Don't bump it; push a tag.
- A promote cancelled by `concurrency: cancel-in-progress` (a second merge landing mid-poll) is also
  expected — the newer run promotes the newer build; nothing is lost.
- **APNs is production for every TestFlight/App Store build.** CI Release archives inject
  `APS_ENVIRONMENT=production` / `APNS_ENV=production` (in the `ios-archive` composite action); only
  dev-sideload builds (the `ios.yml` `workflow_dispatch` dev-IPA path and ssh-mac) stay
  `development`/`sandbox`. The `Config.xcconfig` values are the dev default, overridden for distribution.

### App Store releases are tag-driven (`git push vX.Y`)

Pushing a **`vX.Y`** tag runs `.github/workflows/ios-release.yml` (capability `ios-appstore-release`),
which builds an `X.Y` archive (version injected from the tag — committed source is never bumped, so the
alpha channel is untouched), uploads it to App Store Connect, **finds-or-creates** the `X.Y` App Store
version record, and **attaches** the build — then **stops before Submit** (the listing / screenshots /
privacy that App Review needs live in other workspaces; a human clicks Submit once they're ready).

- **Version scheme is two-part** (`v1.0` → store version "1.0"); a hotfix is a minor bump (no `X.Y.Z`).
  A malformed tag fails the run.
- **Guards**: the tag must be an ancestor of `origin/main` **and** every check-run on that commit must
  be green (including the allowed-red `ios-deliver`/`ios-promote`). A commit whose full pipeline isn't
  green does not reach the store. **Escape hatch:** a cancelled/red `ios-promote` on your target commit
  blocks the release — re-run that idempotent job to green, then re-push the tag.
- Tags fire **only** this workflow: `build.yml` and `ios.yml` exclude tags from their `push` triggers.
- It posts **no** required status check (not in `main.json`); a failed release is red but blocks nothing.
  It reuses the existing Admin ASC key — no new secret. Build number = the release run's `run_number`.

### Sideload a dev IPA (skip TestFlight)

Dev IPAs are produced **on demand by the ssh-mac build loop** (see *Headless macOS build loop* below) —
build → dev-sign → `scp` back → install over usbmuxd. There is **no CI dev-IPA artifact**; CI delivers
only to TestFlight on `main`. This is an **operator runbook, not CI behavior**.

One-time setup (per device):
- Register the device UDID at developer.apple.com → Devices (SE2 is `00008030-0018703A1A7A402E`,
  obtainable via `ideviceinfo -k UniqueDeviceID`). The dev profile only includes registered UDIDs.
- Enable Developer Mode (dev-signed apps won't launch without it). Note `pymobiledevice3 amfi
  enable-developer-mode` **hangs over the usbmux bridge** and the Settings → Privacy & Security →
  **Developer Mode menu only appears after a dev-signed app is installed** — so install a dev IPA
  first, then toggle Developer Mode on in Settings (software restart, no hardware buttons).

Install a dev IPA you already have (run Python tools via `uvx`, never a global install — pymobiledevice3
wants the **bare** socket path, no `UNIX:` prefix):
```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
uvx pymobiledevice3 apps install <path>/SnapSync.ipa
```
(Install goes over `installation_proxy`/lockdownd — no developer tunnel needed. Launch, screenshot,
and other DVT services do need the tunnel + DDI, reached headless via `--userspace` above.)
**Reinstall hangs if the app is running** — `installation_proxy` stalls at "…% Complete" forever when
replacing a **running** app (the first install of a fresh session is fine because nothing is running
yet). SnapSync ignores SIGTERM (see the black-screen trap), so **SIGKILL it first**: `dvt signal <pid>
9 --userspace`, then install. Get `<pid>` from the last `dvt launch` (it prints it).

### Headless macOS build loop (ssh-mac)

For a fast **iterate** loop (not just install), `.github/workflows/ssh-mac.yml` opens a long-lived
`macos-26` job with an SSH server the sandbox connects to, so you can `rsync → build → test → dev-sign →
scp back → install` many times against one **warm** runner instead of one CI run per change. It is
**dispatch-only, non-gating** dev infrastructure (no spec; rationale in the workflow header). Public repo
⇒ the runner is **free**; the session self-closes after `stop_after` minutes (default 90) or when you
`touch /tmp/ssh-mac-stop`. This is an **operator/agent runbook, not CI behavior**.

The auth model: you pass your **public** key at dispatch (safe — a pubkey is public and the private half
never leaves the sandbox); the runner authorizes exactly that key on its own sshd, fronted by a
**cloudflared** quick tunnel (relays encrypted TCP only). **No ASC key ever touches the box** — the runner
holds only the dev cert plus a pre-generated dev provisioning profile baked in as the
`DEV_PROVISIONING_PROFILE_BASE64` secret (a profile carries no private keys, so it is safe as a secret).
`cloudflared` is fetched to the scratchpad, **not** globally installed.

```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
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
# 5. Connect (runner user is `runner`)
alias sshmac='ssh -i "$S/ssh-mac" -o StrictHostKeyChecking=no \
  -o ProxyCommand="'"$S"'/cloudflared access ssh --hostname %h" runner@<HOST>'
# 6. Iterate. NB: $RUNNER_TEMP is UNSET in an ssh shell (it is a GH-Actions-step var) — write outputs
#    under $HOME, not $RUNNER_TEMP, or paths resolve to read-only "/".
rsync -az --delete -e "..." --exclude .git --exclude build --exclude .gradle ./ runner@<HOST>:snapsync/
sshmac 'cd snapsync && ./gradlew iosSimulatorArm64Test'
# 6a. Build an UNSIGNED archive (compiles the Kotlin frameworks + assembles app+appex). The Xcode project
#     is CODE_SIGN_STYLE=Automatic, which needs -allowProvisioningUpdates + the Admin ASC key (absent
#     here) — so a *signed* archive is impossible on the box. Build unsigned, re-sign by hand (6b).
#     BUILD DEBUG, NOT RELEASE. `-configuration Debug` links `linkDebugFramework`, skipping the
#     Kotlin/Native LLVM optimizer that dominates a Release link — and it reruns FULLY on every relink,
#     so it costs you on every iterate, not just cold. Measured on the warm runner (macos-26, 3 cores,
#     Xcode 26.5, ~/.konan warm), archive of a ONE-FILE Kotlin change: Release 449s vs Debug 57s (~8×);
#     cold-from-empty-build/: Release 523s vs Debug 348s; no-op rebuild ~30s either way. The dev/sideload
#     IPA needs no optimization (ios.yml's on-demand dev path already builds Debug for exactly this), and
#     the Debug archive is a complete installable bundle (arm64 app binary + BackgroundUploadExtension.appex
#     in Extensions/) — the 6b re-sign is config-agnostic, so ONLY this -configuration line changes. Switch
#     to Release only when you need an optimization-representative build. Keep the cold cost paid once: never
#     wipe build/ or .gradle between iterates (the step-6 rsync already excludes them) and keep the Gradle
#     daemon alive (no --no-daemon) — an incremental Debug iterate is then ~1 min.
sshmac 'cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
          -destination "generic/platform=iOS" -archivePath "$HOME/artifacts/SnapSync.xcarchive" \
          CODE_SIGNING_ALLOWED=NO archive'
# 6b. Manually re-sign the archive INSIDE-OUT with the baked profiles, then repackage the IPA.
#     WHY not `xcodebuild -exportArchive`: automatic-signing export does NOT reuse manually-installed
#     profiles without an ASC key (fails "No profiles for 'app.snapsync…' were found"); and the
#     CODE_SIGNING_ALLOWED=NO archive has EMPTY entitlements, so any export ships an IPA that aborts at
#     launch on the App-Group container ("client is not entitled"). Re-signing with the entitlements
#     RESOLVED inside each profile (app-groups/keychain/aps/get-task-allow) is the working path.
sshmac 'bash -se' <<'SIGN'
set -e; cd "$HOME/artifacts"
PD="$HOME/Library/MobileDevice/Provisioning Profiles"
ID=$(security find-identity -v -p codesigning | awk '/Apple Development/{print $2; exit}')
APP="SnapSync.xcarchive/Products/Applications/SnapSync.app"
EXT="$APP/Extensions/BackgroundUploadExtension.appex"          # iOS 26 uses Extensions/, NOT PlugIns/
for p in "$PD"/*.mobileprovision; do                          # match each profile by its bundle id
  aid=$(security cms -D -i "$p" | plutil -extract Entitlements.application-identifier raw -)
  case "$aid" in
    *.app.snapsync.BackgroundUpload) security cms -D -i "$p" | plutil -extract Entitlements xml1 -o ext.plist -; cp "$p" "$EXT/embedded.mobileprovision";;
    *.app.snapsync)                  security cms -D -i "$p" | plutil -extract Entitlements xml1 -o app.plist -; cp "$p" "$APP/embedded.mobileprovision";;
  esac
done
codesign -f -s "$ID" --entitlements ext.plist "$EXT"          # sign the extension first (inside-out)…
codesign -f -s "$ID" --entitlements app.plist "$APP"          # …then the app (statically-linked, no nested dylibs)
codesign -v "$EXT" && codesign -v "$APP"
rm -rf Payload && mkdir Payload && cp -R "$APP" Payload/ && zip -qry SnapSync.ipa Payload
SIGN
scp -o ProxyCommand=... runner@<HOST>:artifacts/SnapSync.ipa "$S/"
uvx pymobiledevice3 apps install "$S/SnapSync.ipa"                          # over usbmuxd, as above
sshmac 'touch /tmp/ssh-mac-stop'                                            # end the session
```
Same one-time device prerequisites as *Sideload a dev IPA* (registered UDID + Developer Mode). The
`DEV_PROVISIONING_PROFILE_BASE64` secret is a **tar of both** the app (`app.snapsync`, profile *SnapSync
Dev Push*) and extension (`app.snapsync.BackgroundUpload`, *SnapSync Ext Dev Push*) dev profiles — the
re-sign step above signs both targets. Refresh it when they expire (~yearly) or you register a new
device: dev-export any build, tar both `embedded.mobileprovision` (app's `Payload/*.app/` + extension's
`Extensions/*.appex/`), and `gh secret set`. The non-root sshd + `cloudflared access ssh` handshake were
proven on 2026-07-01; the **unsigned-archive + manual re-sign** path (replacing the earlier
`-exportArchive` claim, which does not reuse installed profiles without an ASC key) was proven on
2026-07-05 — a dev IPA built this way installs and launches on the SE2.

### Verify real uploads

On-device uploads go to the **deployed HTTPS backend** (the device-facing host baked from
`Config.xcconfig`); there is no local upload rig. Confirm an upload landed by checking the backend's
bunny **storage zone** (see `backend/README.md` / `openspec/specs/backend-deployment`), not the app
status screen. Connections are HTTPS-only — default ATS, no `NSAllowsLocalNetworking` exception.

## App Store Connect via API (agent-driven portal chores)

Apple Developer portal tasks that are otherwise GUI-only — code-signing certs, devices,
provisioning profiles, bundle-id capabilities, and App Store / TestFlight text metadata — are
driven through the **App Store Connect API** via the `codemagic-cli-tools` `app-store-connect`
command, run with **uvx** (no install). Credentials are injected as env vars by **`proton-env`**,
which requires **user sign-off on each run** — that approval is the only mutation guardrail (no
bespoke protection on the CI certs), so prefer read-only subcommands and keep mutations
deliberate.

```
# proton-env injects these three (same values as the CI secrets
# ASC_ISSUER_ID / ASC_KEY_ID / ASC_API_PRIVATE_KEY):
#   APP_STORE_CONNECT_ISSUER_ID
#   APP_STORE_CONNECT_KEY_IDENTIFIER
#   APP_STORE_CONNECT_PRIVATE_KEY   # full .p8 PEM content (not a path)

proton-env -- uvx --from codemagic-cli-tools app-store-connect certificates list --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect devices list --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect profiles list --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect bundle-ids list --json
# metadata: app-store-version-localizations (descriptions/keywords),
#           beta-build-localizations (TestFlight "what to test")
```

Covers certs (list/create/revoke), devices (register/enable/disable — Apple has **no delete**),
profiles, bundle-ids + capabilities, and App Store / TestFlight **text** metadata. **Gap:**
screenshot upload (reserve→chunk→commit) has no subcommand — drop to raw REST when a real App
Store listing needs it. The CI key is **Admin** (needed for cloud signing); if an agent should not
reach app metadata / user management, mint a narrower **App Manager** or **Developer** key for
agent use and inject that one instead.

## Modules

```
:domain:engine         sync core + SQL ledger (the only state); no platform deps
:domain:keychain       cross-cutting Keychain access: the ONLY module that may touch SecItem* (a :test:architecture guard enforces it) — three-state read (found/absent/UNREADABLE), mint-only-on-absent, AfterFirstUnlock + in-place migration, ProtectedDataGate (capability architecture-guards; decision record: fix-locked-device-keychain-access)
:domain:logging        cross-cutting diagnostics: logInvocation helper + LogContext ambient prefix + consolidated iOS device-log writers (Kermit-only leaf) (capability diagnostic-logging)
:domain:status         ledger → SyncStatus projection (read-only)
:domain:permission     permission seam (3-state)
:domain:gallery        library resource-enumeration seam + upload-key/role layout (uploadKey, resourceRole, assetIdFromUploadKey, normalizeAssetId) + device manifest + the origin-exclusion rules of the selection policy (SelectionPolicy.excludedAssetIds — screenshots/screen-recordings by mediaSubtype, GIFs by MIME, resolution floors; capability photo-selection-policy). The policy lives HERE because :capability:upload and :domain:status must apply the identical rules and it is the only module both can see
:domain:download-store  app-written download store + read-only SuppressionSource projection (echo-suppression)
:domain:presentation   Orbit MVI container + UiState (Compose-free, no engine dep)
:domain:ui             Compose screens (written against App* only)
:domain:ui:components   App* design system + the Material 3 skin
:capability:upload     upload orchestration: UploadCycle + the UploadJobPlatform seam + DiscoveryStore + UploadConfig + the app-driven BackgroundUploadPump/BackgroundScheduler (jvm()+ios, JVM/harness-covered; deps :domain:engine + :domain:gallery)
:capability:upload-url local edge-URL builder (no network/crypto) — the UploadRequestProvider
:capability:config     deeplink config provisioning (eventId)
:capability:device-id  stable per-install device identity (shared Keychain)
:capability:download   foreign-photo download → stage → import controller (photo-download)
:capability:join       join use-case + DeviceEnroller (writes the per-event device manifest = the physical fact of membership) + EventDetailsSource (join-event)
:capability:album      tested commonMain album orchestration: resolve-or-create the per-event album, dispatch-or-skip an add; PhotoKit behind seams (event-album). Also the album DENYLIST (DENYLISTED_ALBUM_TITLES — WhatsApp, Telegram, …) + the decision-free AlbumManager.assetIdsInAlbums membership lookup it feeds (capability photo-selection-policy)
:capability:push       APNs token registration + PushReceiver seam + EventNotifier (POST /events/<id>/notify) (push-registration, upload-completion-notify)
:capability:membership event-membership lifecycle: extension-side re-join reconciliation + leave use-case + LeaveNotifier (DELETE /events/<id>/devices/<id>) + device-file listing seam
:capability:event-creation-ui  create-event screen seams: EventCreator/CreationStatusSource + HTTP creator
:app:desktop           shared harness library (PhoneFrame + StatusPane, StatusContainerHost wiring both desktop harnesses reuse) AND the full-stack world harness app (:app:desktop:run): real StatusScreen whose counts EMERGE from :test:world's real ListingSyncStatusSource + a right-pane world inspector driving the world (capability full-stack-harness)
:app:desktop:ui        forge harness (:app:desktop:ui:run): phone frame + control panel that forges any UI state; depends on :app:desktop
:app:ios               iOS app wiring + framework export (thin, untested)
:app:ios:photokit-extension  iOS ≥26.1 background-upload extension: PhotoKit adapter (IosPhotoKitUploadPlatform) + composition root, composing :capability:upload + :app:ios:photokit-discovery — thin, untested (orchestration + tests live in :capability:upload)
:app:ios:photokit-discovery  shared iOS PhotoKit discovery (IosDiscovery: change-token walk + PUT request builder + token archive; IosDiscoveryStore) — consumed by BOTH upload tiers; iosMain-only, no jvm/framework (keeps PhotoKit out of the platform-free :capability:upload)
:app:ios:url-session-upload  app-driven iOS 18–26.0 upload adapters: IosUrlSessionUploadPlatform (background URLSession impl of UploadJobPlatform) + IosBackgroundScheduler (BGTaskScheduler) — runs in the MAIN APP process, composed into SnapSyncKit (no separate target); thin, untested
:test:world            test-only shared infra: a controllable in-memory "world" (backend object store + read-models, MockEngine mini-edge, operator-driven UploadJobPlatform/download fakes) the REAL stack runs against + composition helpers mirroring the extension root; jvm()+iosSimulatorArm64. Consumed by :app:desktop AND :test:integration (capability harness-world-model)
:test:architecture     test-only JVM guards for invariants the compiler cannot express (capability architecture-guards): Konsist — no SecItem* outside :domain:keychain (catches fully-qualified calls, which no linter can see on iosMain); plus the entitlements never raise default-data-protection to NSFileProtectionComplete (which would make every App-Group file unreadable while locked, killing the background tier)
:test:integration      test-only: seam → UI-state integration over :test:world — asserts UiState AND world outcomes (objects landed, ledger COMPLETED, foreign photos imported)
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

Dependency flow: `engine ← status ← presentation ← ui`. Boundaries are compiler-enforced; the
platform backend is selected structurally in the app modules.

## Hard rules

- **Design-system containment.** Only `:domain:ui:components` may import Material 3. **No M3 type
  may appear in any `App*` signature.** `App*` components are semantic, not customizable — params
  carry data/meaning, never appearance; **no `Modifier`/color/shape/textStyle params**. Screens
  use `App*` exclusively (spec `design-system`).
- **DI, not `expect`/`actual`.** Implementations are chosen by manual dependency injection in the
  app modules (composition root). The JVM target needs multiple impls per seam (in-memory fake +
  the controllable harness fake), which `expect`/`actual` cannot express.
- **iOS constrains `commonMain`.** Because iOS targets are present, `commonMain` is limited to the
  common stdlib — JVM-only APIs there break the iOS compile (verify with the proxy task above).
- **`:app:ios` is wiring-only.** `:app:ios` and the `iosApp/` Swift host are a thin, **untestable**
  platform layer. All logic — shared *or* iOS-specific — must live in a `domain`/`capability`
  module under test; nothing testable is parked in the app shell.

## Logging & errors

- Log via **Kermit** (multiplatform). Cross-cutting logging infra lives in **`:domain:logging`**: the
  `logInvocation` enter/exit helper (params + result + duration), the process-global `LogContext`
  ambient prefix, and the consolidated iOS device-log writers (`FileLogWriter`/`PublicNSLogWriter`).
- **Device diagnostics** (capability `diagnostic-logging`): the app and extension are separate
  processes, each writing its **own** verbatim, un-redacted `Documents/debug.log` (the App Group
  container is not pullable — verified). Pull both:
  `pymobiledevice3 apps pull app.snapsync Documents/debug.log` and
  `… app.snapsync.BackgroundUpload Documents/debug.log`. `debug.log` is the **canonical un-redacted
  channel** (os_log redacts `<private>`); it rolls to `debug.log.1` past 10 MB. Every line carries a
  `[<entryPoint>]` prefix (e.g. `[onSilentPush]`, `[process]`) tracing it to what triggered it; wrap
  new platform invocations / entry points with `logInvocation` and, for `scope.launch` work, wrap
  *inside* the launch so the context spans the async body.
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Three standing rules:

1. **Every unit test runs on the iOS simulator too.** Put logic tests in `commonTest` so they run
   on **both** JVM and `iosSimulatorArm64` — JVM is the fast loop, not the only coverage.
   `jvmTest`/`iosTest` hold only driver/cinterop wiring behind a shared contract (e.g.
   `LedgerBackendContract` over the JVM-sqlite vs native driver).
2. **`:app:ios` is wiring-only and untested** (see Hard rules). All logic, shared or iOS-specific,
   lives in tested `domain`/`capability` modules.
3. **Seam ↔ UI-state integration tests** assemble the real `engine → status → presentation` stack
   and assert `UiState` from injected `SyncEvent`s, faking only the execution edge (in-memory
   `LedgerBackend`, fake `UploadRequestProvider`). They live in the test-only **`:test:integration`**
   module (`commonTest` → runs on JVM and simulator), which exists so the test may cross the
   `engine → presentation` boundary production forbids.

The edge-URL builder (`:capability:upload-url`) is pinned by `commonMain` tests on URL composition,
filename percent-encoding (deterministic + injective), and the Content-Type-only header set — pure
string-building, no network or crypto.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`).
- For changes that **add, alter, or remove behavior**, drive it through the **OpenSpec** flow
  (propose → apply → archive) so `openspec/specs/` stays the contract of record. Purely mechanical
  work — build/CI, dependency bumps, behavior-preserving refactors, docs — can skip OpenSpec and
  just branch → PR → `/ship`. Use judgment on the line between the two.
- **The `openspec` CLI is not installed** — there is no global binary and no `package.json`. Invoke
  it via npx, pinned to the version CI uses: `npx --yes @fission-ai/openspec@1.5.0 <cmd>` (e.g.
  `… validate --specs --strict`, matching `.github/workflows/build.yml`). Do not run a bare
  `openspec …`; it will fail with "command not found". The generated skills below say bare
  `openspec …` — translate each call to the pinned npx form.
- **The `.claude/opsx` skills and commands are generated**, not hand-written. They assume the
  machine-global profile in `~/.config/openspec/config.json` is `core` (workflows propose ·
  explore · apply · sync · archive) with `delivery: both`. Regenerate with
  `npx --yes @fission-ai/openspec@1.5.0 config profile core` then `… update`, and commit the
  output verbatim — hand-edits are overwritten on the next update. On a default profile, `update`
  emits only four workflows and **deletes** the `sync` skill/command.
