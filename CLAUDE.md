# CLAUDE.md

SnapSync v1 — a personal one-way iOS photo backup to S3 (Kotlin Multiplatform + Compose),
shipped via TestFlight. The JVM desktop app is test equipment, not a product.

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor.
(Two upload tiers per OS version: OS-driven PhotoKit on iOS ≥26.1, app-driven background `URLSession`
on iOS 18–26.0 — see `docs/design.md` and the `ios-photokit-upload` / `ios-url-session-upload` specs.)
(`gradle/libs.versions.toml` is the source of truth for versions.)

<!-- Maintainer note: reference docs/design.md by path, do NOT @-import it — it's ~750 lines and
     @-imports load fully into every session, blowing the context budget. Read-on-demand is intended. -->

## Read first

- **`docs/design.md`** is the design source of truth — architecture, the platform seam, the
  ledgered engine, sync semantics, UI rules, and every resolved/open decision. Read it before
  changing behavior; do not restate or contradict it here.
- **`openspec/specs/`** holds the per-capability specs; **`openspec/changes/`** holds in-flight
  and archived change proposals.

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
device. See `docs/design.md §5.1`.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (module `:app:desktop`): the same
real status screen on the left — but its counts **emerge** from `:test:world`'s real `ListingSyncStatusSource`
(never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
**Invoke extension** (one `process()`-shaped cycle + download reconcile), the gallery/backend, the
upload-job queue and downloads, failure levers, and an engine-console footer. The operator plays the OS
(nothing auto-runs). See `docs/design.md §5.1` (capability `full-stack-harness`).

## On-device iOS (agent-driveable over USB)

The iOS upload extension is **physical-device-only** on the iOS 27 beta (`docs/design.md §6`), and
its *upload trigger* (`processJobs()`) is OS-scheduled — it cannot be forced. But everything around
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

`SNAPSYNC_DEEPLINK` is a **dev/test trigger** (capability `ios-app-shell`): on launch the app
forwards it through the same path as a scanned QR, (re)provisioning the event. It is read **once per
process** and is inert in production (a launch env var is only injectable via a developer launch).
**Note:** re-provision no longer forces a fresh whole-library upload — it now **reconciles against
storage** (the `event-rejoin-reconciliation` join seeds already-stored photos as `COMPLETED` before
enabling), so a relaunch against an event that already has objects uploads **nothing new**. To
observe real uploads in the dev loop, point at a **fresh event id** (or clear the event's objects in
the bunny zone) so the reconcile finds nothing to seed.

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
gated:** taps / UI gestures need a signed **WebDriverAgent** (`developer wda`), and `processJobs()`
**timing** is OS-owned — a re-provision reliably triggers an invocation but you cannot force *when* it
runs.

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
# 6. Iterate
rsync -az --delete -e "..." --exclude .git --exclude build --exclude .gradle ./ runner@<HOST>:snapsync/
sshmac 'cd snapsync && ./gradlew iosSimulatorArm64Test'
sshmac 'cd snapsync && xcodebuild -exportArchive -exportOptionsPlist iosApp/ExportOptionsDevelopment.plist \
          -archivePath "$RUNNER_TEMP/SnapSync.xcarchive" -exportPath out'   # no ASC key: reuses installed profile
scp -o ProxyCommand=... runner@<HOST>:snapsync/out/SnapSync.ipa "$S/"
uvx pymobiledevice3 apps install "$S/SnapSync.ipa"                          # over usbmuxd, as above
sshmac 'touch /tmp/ssh-mac-stop'                                            # end the session
```
Same one-time device prerequisites as *Sideload a dev IPA* (registered UDID + Developer Mode). The
`DEV_PROVISIONING_PROFILE_BASE64` secret is a **tar of both** the app (`app.snapsync`) and extension
(`app.snapsync.BackgroundUpload`) dev profiles — the archive signs both targets. Refresh it when they
expire (~yearly) or you register a new device: dev-export any build, tar both `embedded.mobileprovision`
(app's `Payload/*.app/` + extension's `.appex/`), and `gh secret set`. The non-root sshd, the
`cloudflared access ssh` handshake, and in-session export were proven on 2026-07-01.

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
:domain:status         ledger → SyncStatus projection (read-only)
:domain:permission     permission seam (3-state)
:domain:gallery        library resource-enumeration seam + upload-key/role layout (uploadKey, resourceRole, assetIdFromUploadKey, normalizeAssetId) + device manifest
:domain:download-store  app-written download store + read-only SuppressionSource projection (echo-suppression)
:domain:presentation   Orbit MVI container + UiState (Compose-free, no engine dep)
:domain:ui             Compose screens (written against App* only)
:domain:ui:components   App* design system + the Material 3 skin
:capability:upload     upload orchestration: UploadCycle + the UploadJobPlatform seam + DiscoveryStore + UploadConfig + the app-driven BackgroundUploadPump/BackgroundScheduler (jvm()+ios, JVM/harness-covered; deps :domain:engine + :domain:gallery)
:capability:upload-url local edge-URL builder (no network/crypto) — the UploadRequestProvider
:capability:config     deeplink config provisioning (eventId)
:capability:device-id  stable per-install device identity (shared Keychain)
:capability:download   foreign-photo download → stage → import controller (photo-download)
:capability:rejoin     extension-side re-join reconciliation + leave use-case + device-file listing seam
:capability:event-creation-ui  create-event screen seams: EventCreator/CreationStatusSource + HTTP creator
:app:desktop           shared harness library (PhoneFrame + StatusPane, StatusContainerHost wiring both desktop harnesses reuse) AND the full-stack world harness app (:app:desktop:run): real StatusScreen whose counts EMERGE from :test:world's real ListingSyncStatusSource + a right-pane world inspector driving the world (capability full-stack-harness)
:app:desktop:ui        forge harness (:app:desktop:ui:run): phone frame + control panel that forges any UI state; depends on :app:desktop
:app:ios               iOS app wiring + framework export (thin, untested)
:app:ios:photokit-extension  iOS ≥26.1 background-upload extension: PhotoKit adapter (IosPhotoKitUploadPlatform) + composition root, composing :capability:upload + :app:ios:photokit-discovery — thin, untested (orchestration + tests live in :capability:upload)
:app:ios:photokit-discovery  shared iOS PhotoKit discovery (IosDiscovery: change-token walk + PUT request builder + token archive; IosDiscoveryStore) — consumed by BOTH upload tiers; iosMain-only, no jvm/framework (keeps PhotoKit out of the platform-free :capability:upload)
:app:ios:url-session-upload  app-driven iOS 18–26.0 upload adapters: IosUrlSessionUploadPlatform (background URLSession impl of UploadJobPlatform) + IosBackgroundScheduler (BGTaskScheduler) — runs in the MAIN APP process, composed into SnapSyncKit (no separate target); thin, untested
:test:world            test-only shared infra: a controllable in-memory "world" (backend object store + read-models, MockEngine mini-edge, operator-driven UploadJobPlatform/download fakes) the REAL stack runs against + composition helpers mirroring the extension root; jvm()+iosSimulatorArm64. Consumed by :app:desktop AND :test:integration (capability harness-world-model)
:test:integration      test-only: seam → UI-state integration over :test:world — asserts UiState AND world outcomes (objects landed, ledger COMPLETED, foreign photos imported)
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

Dependency flow: `engine ← status ← presentation ← ui`. Boundaries are compiler-enforced; the
platform backend is selected structurally in the app modules.

## Hard rules

- **Design-system containment.** Only `:domain:ui:components` may import Material 3. **No M3 type
  may appear in any `App*` signature.** `App*` components are semantic, not customizable — params
  carry data/meaning, never appearance; **no `Modifier`/color/shape/textStyle params**. Screens
  use `App*` exclusively (`docs/design.md §5`).
- **DI, not `expect`/`actual`.** Implementations are chosen by manual dependency injection in the
  app modules (composition root). The JVM target needs multiple impls per seam (in-memory fake +
  the controllable harness fake), which `expect`/`actual` cannot express (`docs/design.md §2`).
- **iOS constrains `commonMain`.** Because iOS targets are present, `commonMain` is limited to the
  common stdlib — JVM-only APIs there break the iOS compile (verify with the proxy task above).
- **`:app:ios` is wiring-only.** `:app:ios` and the `iosApp/` Swift host are a thin, **untestable**
  platform layer. All logic — shared *or* iOS-specific — must live in a `domain`/`capability`
  module under test; nothing testable is parked in the app shell.

## Logging & errors

- Log via **Kermit** (multiplatform).
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Three standing rules (full detail: `docs/design.md §6`):

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
  it via npx, pinned to the version CI uses: `npx --yes @fission-ai/openspec@1.4.1 <cmd>` (e.g.
  `… validate --specs --strict`, matching `.github/workflows/build.yml`). Do not run a bare
  `openspec …`; it will fail with "command not found".
