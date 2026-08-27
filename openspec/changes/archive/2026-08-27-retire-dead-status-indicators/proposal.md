## Why

`StatusIndicator` declares seven cases. **Two are ever constructed** — `AppErrorBanner` passes `Error`,
`CreateEventScreen` passes `Loading`. The other five (`Success`, `Waiting`, `Photos`, `InProgress`,
`Complete`) are reachable from nothing: no screen, no test, no harness preset, no forge control. The
seven remaining references to them are the renderer's own `when` branches.

Git history says they were **abandoned, not aspirational**: each had its construction sites removed by
a redesign, and one of those commits is titled *"dead-component sweep"* — a sweep that missed them.

Auditing `design-system` before deleting anything turned up the reason that matters more than the dead
code. The spec carries a scenario, *"Progress is expressed as meaning, not styling"*, requiring that a
screen displaying an in-progress pass **passes `StatusIndicator.InProgress` to `StatusHero`**. No screen
does. Progress moved to `AppStatusLine`/`AppSyncStatus` in a redesign, and the spec never followed. It
is the one false symbol-level claim in the document.

The audit's other finding is that there is nothing else to fix. `design-system` describes components by
role and contract rather than by symbol name — deliberately — and of its 17 symbol-level claims, 15 are
correct and one (`AppButton(role = …)`) is a counter-example the spec raises in order to forbid it. It
even has a requirement of its own for the status line; what it does not do is name the Kotlin symbol.

## What Changes

- **`StatusIndicator` becomes a two-case enum.** `enum class StatusIndicator { Loading, Error }`
  replaces the seven-case sealed interface. Every case is a payload-free `data object` today, and
  `enum class` is Kotlin's form for exactly that; the repo already accepts an enum in this position
  (`:ui:components` takes `api(:domain)` for the `Arrow` enum in `AppStatusLine`'s signature).
  `IndicatorIcon`'s `when` shrinks from seven branches to two and stays exhaustive without an `else`.
- **The KDoc's justification is corrected.** It currently argues the sealed form is needed because
  *"[Progress] even carries a payload"* — a link to a case that does not exist, describing a property no
  case has. That reasoning belongs to `AppSyncStatus`, whose `Syncing(upload, download)`,
  `NotStarted(startsAt)` and `NeedsAccess(prompt)` genuinely do carry payloads.
- **What the dead branches held goes with them**: `LedDot`, the `LedYellow` colour it alone used, and
  three of the four `Icons.Outlined.*` imports.
- **The stale scenario is REMOVED, not rewritten.** Its principle is already stated — correctly, about a
  live component, with symbols verified — by the capability's own **"App status-line component"**
  requirement, in *"Pulsing arrow drives the ongoing label"*: the status line is given
  `Syncing(upload = Pulsing, download = Hidden)` and renders it *"with no counts and no exposed
  appearance parameters"*. A scenario that is both false and redundant costs nothing to lose, and the
  requirement that keeps the principle is the one describing the component that actually renders it.
- **Not changed**: `StatusHero(indicator, headline, detail?)`'s signature, the variant axis itself, and
  every other `design-system` requirement. Two live cases with two distinct call sites remain a real
  variant axis, and the rule that variants are sealed VALUES rather than separate components is
  deliberate — collapsing it to reach one case would invert that rule the first time a third state
  appears.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `design-system`: the component inventory names two `StatusIndicator` cases instead of seven, and the
  scenario requiring `StatusIndicator.InProgress` be passed to `StatusHero` is removed — the principle
  it asserted is carried by the status-line scenarios, which describe the component that actually
  renders progress.

## Impact

- **`:ui:components`**: `StatusIndicator.kt` (sealed interface → enum, KDoc corrected), `StatusHero.kt`
  (five `when` branches, `LedDot`, `LedYellow` and three icon imports removed). No `App*` signature
  changes.
- **`:ui:screens`**: none. Both live construction sites already pass a surviving case.
- **Tests**: none reference the five cases; the `when` carries no `else`, so the compiler names anything
  missed.
- **Harness**: none. No forge preset, control-panel entry or harness-driver path constructs a
  `StatusIndicator`.
- **Dependencies**: none added or removed.
- **Risk**: the deletion is compiler-checked end to end. The only judgement in it is whether the five
  cases should return, and the history says they were removed one redesign at a time rather than parked.
