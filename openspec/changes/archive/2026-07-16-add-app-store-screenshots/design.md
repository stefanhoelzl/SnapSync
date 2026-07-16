## Context

The App Store listing and a future landing page need screenshots of SnapSync. Producing
them today means a physical device and hand-driving the UI into each state — slow, manual,
and non-reproducible. Two existing facts make an automated path cheap:

- The shared `StatusScreen` renders offscreen deterministically, and its state is a pure
  function of the sources feeding `StatusContainerHost` (`domain/presentation`).
- GitHub-hosted `macos-26` runners boot iOS simulators, so the **real iOS chrome** — a
  status bar cleanable to 9:41 via `simctl status_bar override`, system fonts, exact
  App Store device resolutions — is available in CI without a device.

The chosen middle path is **macOS runner + iOS simulator**, not a Linux Compose Desktop
render (loses iOS chrome) and not real on-device capture (the USB-bridged iPhone is only
reachable from the codehydra sandbox, never a GitHub runner — so it cannot be a CI stage).

Two established repo patterns shape the design:

- **The desktop forge harness** (`desktop-test-harness`) forges *sources* into a real
  `StatusContainerHost` and renders `container.stateFlow` — never a static `UiState`. Its
  `PanelController` deliberately forges only states "the real reduction never sees" cannot
  arise — i.e. only reduction-reachable frames.
- **`ssh-mac.yml`** is dispatch-only, non-gating dev infra with **no spec** — its rationale
  lives in the workflow header.

## Goals / Non-Goals

**Goals:**
- Generate App-Store-credible iPhone screenshots reproducibly in CI, on demand.
- Reuse the shared `StatusScreen` and the real reduction, so a shot can never depict a
  frame the app cannot actually produce.
- Keep `:app:ios` wiring-only: all state-selection logic lives in a tested module.
- Preserve the `ios-app-shell` "renders live `UiState`, not a static one" invariant.

**Non-Goals:**
- Auto-upload to App Store Connect (raw REST reserve→chunk→commit). Assets ship as a
  workflow artifact; a human places them. Deferred.
- Visual-regression diffing / gating. This stage never fails a build.
- Additional locales (English only), iPad sizes, and 6.5″/older iPhone classes (6.9″ is the
  single App-Store-required class and auto-scales down).
- The `Syncing` state — its pulsing arrow animates, making capture non-deterministic.

## Decisions

### D1: Forge the sources into a real container (not a static `UiState` bypass)

The `SNAPSYNC_FORGE_STATE` trigger assembles a `StatusContainerHost` over forged sources and
renders `container.stateFlow`, mirroring the desktop forge harness.

**Why, over mounting a static `UiState` directly (`StatusScreen(Joined(InSync), …)`):**
- The `ios-app-shell` invariant ("renders live … not a static `UiState` … not a placeholder")
  stays **unamended** — the forged path is still a live container.
- It inherits the harness principle of **reduction-reachable frames only**, so a marketing
  shot can never lie about the product (aligns with Apple's "screenshots reflect the app").
- It is also the *cheaper* option: `StatusContainerHost` already defaults
  `attestedSource = AlwaysAttested` and `downloadSource = InMemoryDownloadStatusSource()`, so
  a clean `Joined(InSync)` needs only three forged inputs — `permission = GRANTED`, a
  `config` with a past `startsAt` + name, and a settled `SyncStatus.Ready` — with no backend,
  token, or photo access.

The static-bypass alternative won only on pixel-exact control, which the "no impossible
frames" principle makes undesirable here.

### D2: The forge factory lives in `:domain:presentation` (`commonMain`, tested)

A factory maps a recognized state name → forged sources → a configured `StatusContainerHost`.
It sits in `:domain:presentation` because that module owns `UiState`/`StatusContainerHost`,
is `commonMain` (visible to `:app:ios`), Compose-free, and testable. Its test in `commonTest`
asserts each recognized name yields the intended `container.stateFlow` frame — running on both
JVM and `iosSimulatorArm64`.

Any trivial constant sources the factory needs (a `SyncStatusSource` emitting a fixed `Ready`,
a constant `PermissionStatusSource`/`ConfigSource`) live in `commonMain` **main**, idiomatic
alongside the existing `AlwaysAttested` / `InMemoryDownloadStatusSource` / `NoOpEventCreator`
constants — **not** in `:test:world`, which the production app cannot depend on.

`:app:ios` gains exactly one branch: read `SNAPSYNC_FORGE_STATE`, and if set, mount the
factory's host instead of `SnapSyncRoot`'s — the same untested-shell shape as the existing
`SNAPSYNC_DEEPLINK` read.

### D3: The workflow is unspec'd CI infra (`ssh-mac` precedent), folded into one change

`screenshots.yml` changes no product behavior; it is dispatch-only and non-gating and emits
**candidate** assets a human then uploads. CLAUDE.md explicitly lets build/CI skip OpenSpec.
So the only spec'd piece is the forge trigger (a real app-shell behavior), folded into
`ios-app-shell`. The workflow's rationale (device class, states, brand band, artifact-not-
auto-upload, non-gating) lives in the workflow header and this document — no standing
`app-store-screenshots` capability.

### D4: 6.9″ only, brand-banded, three states, artifact output

- **Device**: iPhone 16 Pro Max (6.9″, 1320×2868) — Apple's single required iPhone class,
  auto-scaled to smaller listings.
- **States** (English): `create` (`CreateEvent`), `joining` (`Joined` + invite QR),
  `in_sync` (`Joined(InSync)`, event name e.g. "Anna's Birthday"). Headlines start as
  editable placeholders ("Start an event in seconds" / "One scan — everyone's in" /
  "Every photo, shared automatically").
- **Framing**: raw `simctl io screenshot` at device resolution, status bar overridden to
  9:41 / full battery / full signal, composited onto a brand-green (`#0E9D6B`) band with a
  headline via ImageMagick.
- **Output**: `actions/upload-artifact` (e.g. `en-US/6.9/*.png`).

### D5: Capture loop per state

For each state: `simctl launch --env SNAPSYNC_FORGE_STATE=<state>` → `simctl io booted
screenshot` → **SIGKILL** before the next launch (the app ignores SIGTERM; a relaunch over a
live instance risks a black launch screen, per CLAUDE.md's black-screen trap). Status-bar
override is applied to the booted device once after boot.

## Risks / Trade-offs

- **[The simulator `.app` build is unexercised in CI]** → `ios-app-shell` spec's it, but no
  job runs `xcodebuild -sdk iphonesimulator`, and the PhotoKit extension conforms to
  `PHBackgroundResourceUploadExtension` with no `#if targetEnvironment(simulator)` guard.
  **RESOLVED by the spike** (2026-07-15, ssh-mac / `macos-26`, Xcode 26.5): the full `iosApp`
  scheme — extension in the closure — builds for the simulator SDK (`** BUILD SUCCEEDED **`,
  `EXIT=0`), producing `SnapSync.app` (`app.snapsync`). No app-target-only fallback needed; the
  workflow's full-scheme build stands (the fallback stays documented in the workflow as a hedge).
- **[A forged constant source in production code could be misused]** → keep the constants
  minimal and named for forging; the trigger that reaches them is env-gated and inert in
  production, exactly like `SNAPSYNC_DEEPLINK`.
- **[macOS runners are ~10× Linux minutes]** → dispatch-only, so no per-push cost; public
  repo ⇒ runner minutes are free.
- **[Screenshots are Skia/simulator renders, not device captures]** → acceptable: the UI is
  the identical shared Compose code, and the iOS chrome is real (simulator). A final
  on-device authenticity pass remains available out-of-band via the CLAUDE.md `dvt
  screenshot` runbook if ever needed before an actual submission.

## Migration Plan

Additive and reversible. The forge trigger is inert without the env var; deleting
`screenshots.yml` and the factory removes the feature with no production impact (nothing
depends on it). No data model, no gate, no delivery change.

## Open Questions

- Final headline copy and whether the brand band is a flat color or a gradient (placeholders
  ship first; refine without touching the pipeline).
- Whether to later promote the workflow to a thin `app-store-screenshots` capability if it
  grows (locales, iPad, auto-upload). Not now.
