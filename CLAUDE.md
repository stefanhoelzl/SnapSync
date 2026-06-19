# CLAUDE.md

SnapSync v1 — a personal one-way iOS photo backup to S3 (Kotlin Multiplatform + Compose),
shipped via TestFlight. The JVM desktop app is test equipment, not a product.

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 27.0 · Orbit MVI · SQLDelight · Ktor.
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
  Xvfb is required. Only `:app:desktop:run` (below) opens a real window and needs a display.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

## Test UI (review/exercise every UI state)

`./gradlew :app:desktop:run` launches the desktop harness: the real `:domain:ui` status screen
inside a phone-sized frame on the left, and a **control panel** on the right (raw Material 3 — it
is test equipment, never `App*`). The panel **forges any display state** — permission presets,
sync-state presets, and the engine console — so you can review and test all UI states without a
device. See `docs/design.md §5.1`.

## On-device iOS (manual testing)

The iOS upload extension is **physical-device-only** on the iOS 27 beta (`docs/design.md §6`), so
on-device testing is manual. Reach a connected iPhone through the host's usbmuxd — **this is
specific to the codehydra sandbox** (the host socket is bridged at `/run/host/run/usbmuxd`):

```
export USBMUXD_SOCKET_ADDRESS=UNIX:/run/host/run/usbmuxd
idevice_id -l        # list connected devices
ideviceinfo          # device details (UDID, model, iOS version)
idevicesyslog        # live device log — watch the app/extension at runtime
idevicescreenshot    # capture the screen
idevicedebug         # launch/debug an installed app
idevicecrashreport   # pull crash reports
```

### Sideload a dev IPA (skip TestFlight)

CI publishes a **development-signed IPA** as a GitHub Actions artifact on every push
(`snapsync-dev-ipa-<run_number>`, 1-day retention) — install it straight onto a registered device,
no TestFlight. This is an **operator runbook, not CI behavior**. See `openspec/specs/ios-sideload-delivery`.

One-time setup (per device):
- Register the device UDID at developer.apple.com → Devices (SE2 is `00008030-0018703A1A7A402E`,
  obtainable via `ideviceinfo -k UniqueDeviceID`). The dev profile only includes registered UDIDs.
- Enable Developer Mode (dev-signed apps won't launch without it). Note `pymobiledevice3 amfi
  enable-developer-mode` **hangs over the usbmux bridge** and the Settings → Privacy & Security →
  **Developer Mode menu only appears after a dev-signed app is installed** — so install the IPA
  first (below), then toggle Developer Mode on in Settings (software restart, no hardware buttons).

Per build (run Python tools via `uvx`, never a global install — note pymobiledevice3 wants the
**bare** socket path, no `UNIX:` prefix):
```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
gh run download <run-id> -n snapsync-dev-ipa-<run_number> -D /tmp/ipa   # the run's build number
uvx pymobiledevice3 apps install /tmp/ipa/SnapSync.ipa
```
(Install goes over `installation_proxy`/lockdownd — no CoreDevice developer tunnel needed; only
launching *under a debugger* or screenshots/DDI would.)

### Verify real uploads against a local S3 (the `real-s3-upload` loop)

`scripts/local-s3.sh` is the on-device upload test rig (test equipment, no spec). It starts a
**MinIO** S3 server on the LAN via podman, creates the bucket, prints the **upload host** and a
config **QR** (terminal + PNG), and streams every uploaded object live (`mc watch`). The upload
host is **compile-time** (PhotoKit's `BackgroundUploadURLBase` cannot be runtime-configured), so the
loop is:

```
scripts/local-s3.sh                                   # prints UPLOAD_HOST + the QR, then watches
gh workflow run ios.yml --ref <branch> \              # bake that host into a dev IPA
  -f upload_host=http://<lan-ip>:9000
# download + install the dev IPA (sideload steps above), scan the QR (bucket/region/creds),
# trigger a sync — uploaded objects print live in the script's terminal.
```

The QR carries only `bucket`/`region`/`accessKeyId`/`secretAccessKey` (`v=2` payload); the host is
baked. A plain push (no dispatch input) bakes the inert `https://dummy.invalid`, so `main`/TestFlight
is unaffected. **Drain-all is kept** — the ledger stays at `REQUESTED`, so success is confirmed by
the object landing in the bucket (the live stream / MinIO console at `:9001`), **not** by the app
status screen. ATS `NSAllowsLocalNetworking` permits the plaintext PUT; the app primes the Local
Network permission at launch. (Open spike: whether `BackgroundUploadURLBase` accepts `http://` + IP
— if it demands `https`, run MinIO with TLS.)

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
:domain:presentation   Orbit MVI container + UiState (Compose-free, no engine dep)
:domain:ui             Compose screens (written against App* only)
:domain:ui:components   App* design system + the Material 3 skin
:capability:s3         hand-rolled SigV4 presigner
:capability:config     deeplink config provisioning
:app:desktop           test harness (phone frame + control panel)
:app:ios               iOS wiring + framework export (thin, untested)
:test:integration      test-only: seam → UI-state integration (planned)
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

The SigV4 presigner is additionally pinned by **golden/known-answer tests** verified against AWS's
published vector.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`).
- For changes that **add, alter, or remove behavior**, drive it through the **OpenSpec** flow
  (propose → apply → archive) so `openspec/specs/` stays the contract of record. Purely mechanical
  work — build/CI, dependency bumps, behavior-preserving refactors, docs — can skip OpenSpec and
  just branch → PR → `/ship`. Use judgment on the line between the two.
