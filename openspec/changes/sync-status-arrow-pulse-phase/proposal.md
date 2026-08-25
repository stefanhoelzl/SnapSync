## Why

A member reported it from a real device (Bugsink `SNAPSYNC-21`, build 605 / v0.3, iPhone14,2, iOS 26.5.2,
PhotoKit tier): *"arrows are not pulsing in sync"*. The dump shows both arms live at once —
`photos_pending: 88` (upload arrow `Pulsing`) and `downloads_in_flight: 24` with
`downloads_imported 98 < downloads_assets 122` (download arrow `Pulsing`) — and the two arrows beat
against each other instead of together.

Reproduced and measured through the real `StatusScreen` on a controlled clock, reading glyph opacity
(dim `0.0624` ↔ bright `0.1060`, full swing `0.0436`):

| both arrows begin pulsing… | opacity delta, up − down |
|---|---|
| in the same frame (control) | `±0.00003` — lockstep |
| 366 ms apart | `+0.039 → −0.038 → +0.039` — **~90% of full opposition, i.e. near anti-phase** |

The offset is not a corner case, it is the normal path: uploads begin at join and the download arm
reconciles later, so the two arrows essentially never enter together on a device.

## What Changes

- The `App` status-line component drives **both** direction arrows from a **single** pulse phase, so
  two `Pulsing` arrows always render the same opacity at the same instant however far apart they
  started.
- A `Pulsing` arrow that appears while the other is already pulsing **snaps into** the shared phase —
  including entering at the dim end of the fade. Deliberate: lockstep is the point, and a fade-in
  entrance would re-introduce, briefly, exactly the drift being fixed.
- No public signature changes. The shared opacity is internal to the component; `design-system`'s
  semantic-only rule continues to forbid appearance parameters on any `App*` surface.
- No change to arrow **derivation** — which arrow is `Hidden` / `Static` / `Pulsing` is
  `sync-status-screen`'s contract and is untouched. This change is about how a `Pulsing` arrow
  *animates*, which `design-system` already owns.
- Reduced-motion behaviour is unchanged: under the preference neither arrow animates, and both render
  at full opacity.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `design-system`: the *App status-line component* requirement already says the component SHALL animate
  a `Pulsing` arrow; it says nothing about the two arrows' relationship to each other, which is why the
  drift is not a spec violation today. Add the missing guarantee — when both arrows are `Pulsing` they
  animate in lockstep, identical opacity at every instant, regardless of when each began.

## Impact

- `ui/components/src/commonMain/kotlin/app/snapsync/ui/components/AppStatusLine.kt` — the only pulsing
  surface in the codebase. Its private `ArrowIcon` currently builds its own `rememberInfiniteTransition`
  *inside* `if (animate)`, so each arrow's phase starts when that arrow begins animating, and restarts on
  every `Hidden → Pulsing`, `Static → Pulsing`, or reduced-motion flip.
- No other module. `AppStatusLine`'s public signature, `:ui:presentation`'s reduction, and every
  `sync-status-screen` derivation rule are unaffected.
- No new tests and no harness work: the forge cannot currently produce a pulsing download arrow
  (`PanelController.setDownload`'s `inFlight` defaults to `0` and no preset passes it) and the
  harness driver's clock does not advance — both deliberately left as they are. Verification is on
  device instead (see `design.md`).
- Changelog label: `bug`.
