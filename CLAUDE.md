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

- `./gradlew build` — the canonical check (compiles all targets + runs JVM tests).
- **Needs a display on Linux.** Compose Desktop UI tests (`:domain:ui:jvmTest`) touch AWT, so the
  build needs a **live** X server. Start Xvfb on a fresh display and **verify it is up before
  building** — a stale `/tmp/.X<n>-lock` from a prior session makes Xvfb fail to start while
  `DISPLAY` still points at the dead display, and `:domain:ui:jvmTest` then **hangs forever**
  (silent hang, not an error). Locally, pick an unused display number (e.g. `:99`) and clear any
  stale lock first:
  ```
  rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
  Xvfb :99 -screen 0 1920x1080x24 -extension RANDR +extension GLX &
  DISPLAY=:99 xdpyinfo >/dev/null 2>&1 && echo "display up" || echo "display DEAD"
  DISPLAY=:99 ./gradlew build
  ```
  Do not pipe the build through `tail`/`head` — it buffers all output until Gradle exits, hiding a
  hang. (CI uses `:1` on a clean runner, so it never hits the stale lock.)
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
