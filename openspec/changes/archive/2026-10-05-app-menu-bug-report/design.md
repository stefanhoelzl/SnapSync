# Design

## Context

The report sheet already exists end to end. `ScreenLayout`'s `NavTitle` takes `onTitleDoubleTap`, which
dispatches `UiIntent.ReportBugOpen`. The container sets `Overlays.reportingBug`, and `StatusOverlays` draws
`BugReportSheet`. Send dispatches `UiIntent.SendDiagnostics`, which presentation runs fire-and-forget through
`UserCommands.sendDiagnostics`. The composition awaits `collectDiagnosticDump` and
`process.crash.sendDump(...)` on the core lane, and the `DumpResult` (sent, `Saved`, `NotSent`) is only logged.
`UiState.reportDestination` is a build constant that `ComposedApp` fills from the process.

The app already has an open-link command (`UserCommands.openLink`, over `SystemUi.openUrl`). The update-required
screen uses it for the store page. The site origin is the generated `LINK_ORIGIN`, and the Privacy Policy is the
site's `#privacy` anchor (also `metadata/listing/en-US.json`'s `privacyPolicy`). The build's version is
`BuildInfo.appVersion`, and its build number is `BuildInfo.diagnostics.buildNumber`.

The interview and the clickable mockup that settled the UI choices are summarised in proposal.md.

## Goals / Non-Goals

**Goals:**
- One menu, drawn by `:ui:screens` for every layer, that follows the existing rule: *what the screen shows is
  `UiState`*.
- Every new state can be reached through the existing user-intent path, so the rig, the world harness and
  `:test:integration` drive it without new levers.
- The result of a report becomes visible without changing what leaves the device.

**Non-Goals:**
- No change to the dump's content, size bounds, scrub exemption or channel.
- No rate limit, and no tracking of delivery: `sendDump` hands the event to the SDK's queue, and nothing after that
  comes back.
- No in-app web view. Links leave the app.
- No reorganisation of the bottom action cluster (settings · share · leave).

## Decisions

### D1 — The drawer's open state is an overlay flag in `UiState`, not Compose-local state
Add `Overlays.menuOpen`, with `UiIntent.MenuOpen` / `UiIntent.MenuDismiss`, the same way `reportingBug` and
`confirmingLeave` work. The drawer then shows in `UiState`: the world harness opens it through the real tap, and
`/device/state` reports it. Following the rig's rule for overlays (an opener that touches no port is not a `/user`
verb), the menu's intents are listed among the rig's exclusions with their reasons, and what the menu leads to
stays driveable through `/user/sendDiagnostics`.
*Alternative:* hold a Compose `DrawerState` locally, as the screen holds the text typed into a sheet. Rejected
because opening the menu is a navigation state the screen shows, not typing in progress, and a local flag is
invisible to every test host except the Compose UI test.

### D2 — "Report a problem" closes the menu, then opens the sheet
Both happen in one reduction (`menuOpen = false, reportingBug = true`), so the sheet never stacks on the drawer and
dismissing the sheet returns to the plain screen. The double-tap keeps its own intent, unchanged.

### D3 — Where the menu is absent is decided by the reduction
A derived "menu available" is false for the reconfigure surface, for `Layer.CreatingEvent`, and for
`JoinPhase.Detailed` at step `Committing`. It is true everywhere else, including the update-required layer, the
join failure phases and the closed event. The reduction also masks `menuOpen` wherever the menu is not available,
just as it masks joined-only overlays against a non-joined layer. A layer change that removes the button then also
closes an open drawer, with no stale flag left behind.
Details loading (`JoinPhase.Loading`) is *not* excluded: it is not a join in progress, only a fetch, and a slow
fetch is exactly when someone may want to report.

### D4 — The ☰ button sits in the title row; the double-tap stays on the label
`ScreenLayout` gains an optional `onMenu` slot. When it is non-null, an `IconButton` (☰) appears at the start of the
`NavTitle` row, and the label stays centred over the full width (the icon is placed on top of the row, not beside
it, so the label never moves). The button is a real control with a content description ("Menu"), unlike the
gesture. The drawer is Material 3's `ModalNavigationDrawer`, skinned through `:ui:components` as an `App*` component,
because the design system is the only place a Material type may appear.
*Alternative:* a dropdown or a bottom sheet (mocked side by side). The user chose the side drawer.

### D5 — The links are built from the generated site origin, not from new build constants
`Website = LINK_ORIGIN` and `Privacy policy = "$LINK_ORIGIN/#privacy"`, both built in `:domain:model` beside the
`EventLink` codec, which already owns that origin. Both dispatch one `UiIntent.OpenLink(kind)`, which presentation
routes to the existing `UserCommands.openLink`. A refused hand-off is logged at `Error` by the existing
`recordingRefusal`, as the store link is.
*Alternative:* add `website` / `privacyPolicy` to `BuildInfo`. Rejected because the deployment would carry two more
values that the domain already derives from one.

### D6 — Version and build number travel in `UiState` as a build constant
Add `UiState.build: BuildLabel(version, buildNumber)`, filled by `ComposedApp` from `BuildInfo` exactly as
`reportDestination` is. Off-device compositions get `DiagnosticEnvironment.UNKNOWN`'s values, which render as they
are ("unknown"), and the harness shows that honestly.

### D7 — The send command answers its result, and presentation turns it into a short notice
`UserCommands.sendDiagnostics` returns a model-level `ReportOutcome { SENT, SAVED, NOT_SENT }`, mapped from
`DumpResult` in the composition. The presentation intent awaits it, then sets `Overlays.reportNotice: ReportOutcome?`.
The screen shows it as a short message at the bottom of the screen that clears itself after a few seconds or on
tap, through `UiIntent.ReportNoticeDismiss`. The automatic clear is a timer in presentation, not in the UI, so the
reduction stays the single place a flag changes.
SENT is shown as "sent" and makes no delivery claim, because the SDK may queue and retry. NOT_SENT is worded
calmly, in line with the existing requirement "Failures are told calmly".
*Alternative:* keep it fire-and-forget and confirm optimistically when the sheet closes. Rejected because it would
say "sent" for a report that was in fact `NotSent`.

### D8 — The confirmation appears when the dump has been handed off, not when the sheet closes
The sheet still closes immediately on Send, as it does today. Collecting the dump reads about 700 KB of logs, so
the notice arrives a moment later. While the dump is still being collected nothing is shown (no spinner), and the
interval is short. A second report sent before the first answers is allowed (no rate limit). Each produces its own
notice, and the later one replaces the earlier.

## Risks / Trade-offs

- [More reports reach Bugsink, each up to about 700 KB] → Accepted for now (no limit, by decision). Bugsink's
  per-event cap is already guarded by construction (`EventBounds`). Revisit if the volume becomes a problem.
- [The ☰ changes every marketing screenshot] → Re-capture through `screenshots.yml` (iOS and Android) and check
  every image by eye before committing, as the runbook requires.
- [Detekt ceilings in `ui.yml`: a new slot on `ScreenLayout` and a new drawer component add parameters and
  statements] → Bundle the drawer's inputs into one value, as `StatusActions` already does. A ceiling may only go
  down, so if one would have to rise, the PR states why.
- [The drawer gesture (an edge swipe) conflicts with iOS's swipe back] → The app has no navigation stack, so there
  is no back swipe to collide with. `gesturesEnabled` is still limited to while the drawer is open, so an
  accidental edge swipe never opens it.
- [`LINK_ORIGIN` differs between dev and production deployments] → Intended: a dev build links to its own site,
  the same origin its invite links use.

## Migration Plan

UI-only and additive. There is no stored state, so a rollback is a revert. The site's privacy wording and the
screenshots ship in the same PR, so the listing and the site never describe a menu the build lacks.
