## Why

The App Store listing and a future landing page need screenshots of the app, and today
there is no way to produce them without a physical device and hand-driving the UI into
each state. The shared `StatusScreen` already renders offscreen deterministically, and
GitHub-hosted `macos-26` runners can boot iOS simulators — so authentic, on-brand
listing images can be generated reproducibly in CI instead of captured by hand.

## What Changes

- Add a **developer launch-environment trigger `SNAPSYNC_FORGE_STATE`** to the iOS app
  (sibling to `SNAPSYNC_DEEPLINK`): when present, the app assembles a `StatusContainerHost`
  over **forged sources** for the named state and renders it — so the screen still renders
  **live** `container.stateFlow`, never a static `UiState`. Inert in production (a launch
  env var is only injectable via a developer launch), applied once per process.
- Add a **forge factory in `:domain:presentation`** (commonMain, tested) mapping a
  state name to the forged sources for a **reduction-reachable** frame — it can never
  fabricate a state the real reduction cannot produce (the App-Store-honesty constraint,
  mirroring the desktop forge harness's `PanelController`).
- Add a **dispatch-only, non-gating CI workflow** (`.github/workflows/screenshots.yml`,
  `macos-26`) that builds the app for the simulator, boots a 6.9″ iPhone, cleans the
  status bar, launches once per state with `SNAPSYNC_FORGE_STATE`, captures each with
  `simctl io screenshot`, composites each onto a brand-color band with a headline, and
  uploads the set as a workflow artifact. This is CI infra (no product behavior),
  matching the `ssh-mac.yml` precedent — its rationale lives in the workflow header and
  this change's `design.md`, not a standing capability.

Initial marketing set (English only): **Create** (`CreateEvent`), **Joining**
(`Joined` + invite QR), **In sync** (`Joined(InSync)`). Assets are uploaded as an
artifact for a human to place on the listing / landing page — this change does **not**
auto-upload to App Store Connect.

## Capabilities

### New Capabilities
<!-- none — the workflow is unspec'd CI infra (ssh-mac precedent) -->

### Modified Capabilities
- `ios-app-shell`: add the `SNAPSYNC_FORGE_STATE` developer launch-environment trigger
  and the forge-factory contract it mounts (forged sources → real `StatusContainerHost` →
  live render; reduction-reachable frames only; inert in production; once per process).
  The existing "renders live `UiState`, not a static one" invariant is **preserved**, not
  amended — the forged path still renders `container.stateFlow`.

## Impact

- **Code**: `:domain:presentation` gains the tested forge factory (+ any trivial constant
  in-memory sources it needs in `commonMain` main, alongside the existing `AlwaysAttested`
  / `InMemoryDownloadStatusSource` / `NoOpEventCreator`). `:app:ios` gains one launch
  branch (read env → call factory → mount), consistent with the existing `SNAPSYNC_DEEPLINK`
  read; it stays wiring-only.
- **CI**: new `screenshots.yml` (dispatch-only, non-gating). No change to the merge gates
  (`build`, `ios-build`, `ios-test`) or delivery.
- **Risk / spike**: the simulator `.app` build via `xcodebuild -sdk iphonesimulator` is
  spec'd (`ios-app-shell`) but **never exercised in CI today**, and the PhotoKit upload
  extension conforms to `PHBackgroundResourceUploadExtension` with no simulator guard. The
  first task verifies the scheme builds for the simulator with the extension in the
  closure; if it does not, the workflow builds the app target only (the extension is
  irrelevant to a UI screenshot).
- **Out of scope (deferred)**: auto-upload to App Store Connect (raw REST), additional
  locales, iPad sizes, and the `Syncing` state (pulsing arrow → non-deterministic capture).
