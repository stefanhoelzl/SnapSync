# Proposal: sync-status-screen-states

## Why

The status screen currently speaks a two-state vocabulary (Idle / Uploading X of N) that cannot express what a real backup looks like: passes that finish with failures, passes the OS has parked, a device that has never synced, or how long the current pass will take. Completing the display vocabulary now — while the screen is still driven by forgeable snapshots — lets the UI be designed and exercised in the desktop harness before any engine exists behind it.

## What Changes

- **BREAKING (internal seam)**: `SyncStatus` grows from `(pending, completed)` to `(pending, completed, failed, active, estimatedRemaining: Duration?, lastFinishedAt: Instant?)` plus a computed `state` property that classifies the snapshot into six states. The old Idle/Uploading derivation is superseded.
- The status screen renders **six states** as a centered two-line hero (headline + muted detail): No sync yet ⚠, Sync in progress ◔ (+ remaining-time estimate), Waiting to sync ⏳, Sync complete ✓, Sync incomplete ⚠, Sync failed ✖ (each finished outcome shows ticking relative time, e.g. "5 min ago"). Item counts and the linear progress bar leave the screen; the progress icon alone shows the rough fraction.
- Presentation formats all display text: ticking relative time (injected Clock, ~1/min re-emission) and coarse estimate buckets ("~2 min left", "estimating…" while null).
- Design system: `StatusHero(indicator, headline, detail)` with sealed `StatusIndicator` (Success/Warning/Error/Waiting/Progress(fraction)) replaces `StatusText` and `UploadProgress`; `ScreenLayout` gains the vertically-centered-body convention; first icons enter the M3 skin.
- Desktop harness panel: display-override presets for all six states, including forged timestamps and estimates, still routed through `PanelController` (no scenario machinery — display overrides stay outside the scenario system).
- Housekeeping riding along (no spec change): CI GitHub Actions bumped off Node-20-deprecated majors before the 2026-06-16 forced-Node-24 date.

Deliberately out of scope: all controls and intents ("back up now", enable toggle, permission gate), failed-item counts on screen, any engine/capability work, ScenarioStep indirection.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `sync-status-screen`: requirements rewritten in place — six-state snapshot classification replaces the Idle/Uploading mapping; new rendering requirements (two-line hero, icons, no counts); relative-time ticking; estimate formatting; emission-time estimate semantics ("sources mint estimates per snapshot, never persist them"; presentation ages the past, sources own the future).
- `design-system`: `StatusText`/`UploadProgress` requirements replaced by `StatusHero` + sealed `StatusIndicator`; documents the refined rule — distinct components for design-time choices, sealed semantic values for runtime-data-driven variants; `ScreenLayout` centered-body convention.
- `desktop-test-harness`: display-override requirements extended from two snapshot buttons to six state presets with forged timestamps/estimates.

## Impact

- `:domain:sync` — `SyncStatus` fields + computed `SyncState`; `SyncStatusSource` contract unchanged.
- `:domain:presentation` — `UiState` becomes six display-ready variants; container gains injected `Clock`, tick loop, and formatting; orbit-test coverage for classification, ticking, and formatting.
- `:domain:ui` — `StatusScreen` maps six `UiState` variants to `StatusHero`; Compose UI tests per state.
- `:domain:ui:components` — `StatusHero`/`StatusIndicator` added; `StatusText`/`UploadProgress` deleted; `ScreenLayout` body centering; the M3 skin hand-draws its glyph icons (no icons dependency). Time types come from stdlib `kotlin.time` (`Instant`/`Duration`/`Clock`) — no new dependencies at all.
- `:app:desktop` — panel presets via `PanelController`.
- `.github/workflows/build.yml` — action version bumps (no requirement change).
