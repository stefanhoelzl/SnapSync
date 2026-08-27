## 1. The baseline layer

- [x] 1.1 Create `config/detekt/_base.yml` carrying only READINGS of rules — no ceilings — opening with
      that distinction stated: a reading interprets what a rule means and applies everywhere; a ceiling
      describes one scope's measurement and belongs to that scope.
- [x] 1.2 Add its single entry, `style.MagicNumber.ignorePropertyDeclaration: true`, with the argument
      at it: MagicNumber exists to force naming, and a named constant has complied.
- [x] 1.3 Change `registerDetektTier` to layer the baseline beneath the tier config
      (`config.setFrom(files("config/detekt/_base.yml", "config/detekt/$configFile.yml"))`) and make the
      tier config OPTIONAL, so an absent file means the scope sits at the baseline.
- [x] 1.4 Run every tier and confirm all eight are still green, and that `ui` drops from 110 to 74
      `MagicNumber` findings and `core` from 30 to 25 with no code changed.

## 2. The coverage guard inverts

- [x] 2.1 In `DetektTierCoverageTest`, replace the "every tier has a config" assertion with "every
      config belongs to a tier", keeping `_base.yml` and `app-shell.yml` out of the tier set with the
      reason at each.
- [x] 2.2 Keep the subproject assertion untouched and note in the KDoc why it is what makes an absent
      config safe to read as meaning — without it, "at the baseline" and "measured by nothing" would be
      indistinguishable.
- [x] 2.3 Verify by temporarily adding an orphan `config/detekt/nobody.yml` that the guard fails naming
      it, then remove.

## 3. Name what deserves naming

- [x] 3.1 `AppTheme.kt`: replace the 29 colour literals with 22 named tokens, so the seven values
      currently duplicated across the light and dark schemes have one definition each.
- [x] 3.2 Move `AppStatusLine.kt`'s four amber `Color(0x…)` values into the palette, so the design
      system defines colours in one place.
- [x] 3.3 `AppDateTimeField.kt`: name days-per-week and the round-up constant in the calendar
      arithmetic (`(leadingBlanks + daysInMonth + 6) / 7`, `for (col in 0 until 7)`, `row * 7 + col`).
- [x] 3.4 `AppMark.kt`: make the implicit 100-unit authoring grid explicit and give `cardPath(14f, 14f,
      11f)` named arguments or named constants.
- [x] 3.5 Re-run the `ui` tier and record the remaining `MagicNumber` count.

## 4. The glyphs

- [x] 4.1 Replace `StatusHero.kt`'s four hand-drawn glyphs — check, cross, clock, photo — with
      `Icons.Outlined.Check` / `Close` / `Schedule` / `PhotoLibrary`, keeping `StatusHero(indicator,
      headline, detail?)`'s signature and the `StatusIndicator` cases untouched.
- [x] 4.2 Remove `GlyphScope` and the `x()`/`y()` fraction helpers if nothing else uses them.
- [x] 4.3 Review every indicator state side by side in the forge harness (`:test:harness-driver`)
      before and after. If the Material equivalents read worse, stop and revisit D4 rather than
      shipping a downgrade.

## 5. StatusActions

- [x] 5.1 Declare `StatusActions` in `:ui:screens` holding `StatusScreen`'s 18 callbacks, each field
      defaulted exactly as the parameter is today so every host keeps its per-callback opt-in.
- [x] 5.2 Replace those 18 parameters with `actions: StatusActions = StatusActions()`, leaving `state`
      and the seven data parameters alone.
- [x] 5.3 Update the three shell call sites — `MainViewController.kt`, `ForgeViewController.kt`,
      `StatusPane.kt` — and the three `:ui:screens` test files.
- [x] 5.4 Confirm the container still performs the domain-to-screen adaptation, and that no screen
      names `eventId` or a domain argument order it did not name before.

## 6. Decompose and split

- [x] 6.1 Decomposed where it was meaningful and stopped where it was not: `StatusOverlays` and
      `JoinedBottomActions` out of `StatusScreen` (138→71 statements, cc 30→18), `JoinSelection.kt` out
      of the join gate (cc 35→16), and `ReconfigureScreen` (cc 28→17) by REUSING those resolvers instead
      of restating them. Stopped at 1-3 points over the ceiling per screen — see D8.
- [x] 6.2 Split `StatusScreen.kt` (1489 lines) into one file per screen.
- [x] 6.3 Split `AppDateTimeField.kt` (887 lines) into the date picker, the range picker, the calendar
      grid and the time wheels.
- [x] 6.4 Confirm every `:ui:screens` and `:ui:components` test still passes, and drive every UI state
      through the forge harness to confirm nothing moved visually.

## 7. The one-liners, and the deletion

- [x] 7.1 Fix `MayBeConst` at `AppStatusLine.kt:107`.
- [x] 7.2 Fix `TopLevelPropertyNaming` at `AppDateTimeField.kt:699`.
- [x] 7.3 Fix `MatchingDeclarationName` at `StatusHero.kt:31`.
- [x] 7.4 Shrink `config/detekt/ui.yml` to `LongParameterList` alone, at its measured number, with the
      argument at it (D8): a parameter bundle is itself a constructor, so this rule cannot be satisfied
      by bundling. Confirm every OTHER rule passes from the baseline with nothing in the file.

## 8. Screenshots — NOT NEEDED (measured)

- [x] 8.1 Established that the glyph swap reaches NO captured state, so no re-capture is performed:
      `AppStatusLine` renders the check/clock/warning seen in `create`/`joining`/`in_sync` and draws its
      own `Icons.Filled.*` without ever calling `IndicatorIcon`; the swapped glyphs are reachable only
      via `StatusHero` (called once, with the unswapped `Loading` spinner) and `AppErrorBanner`, which
      no captured state shows because none of the three is a failure state.
- [x] 8.2 Measured it both ways rather than reasoning alone: the swap changes 348 pixels (0.11%) of a
      390x844 frame, all within one 26x26 box at the indicator, and the committed `in_sync-light.png`
      carries no error banner and no hero.
- [x] 8.3 Recorded the correction in design.md (D6), which had claimed the glyphs render on every
      captured state — the reason the original plan bundled a ~15 minute screenshot run that would have
      committed six byte-identical files.

## 9. Verify

- [x] 9.1 `./gradlew build` green, with all eight tiers and `detektAppShell` passing.
- [x] 9.2 Confirm no tier other than `ui` changed its numbers or its register.
- [x] 9.3 Update `CLAUDE.md`'s complexity paragraph to describe the baseline layer and what an absent
      tier config means.
- [x] 9.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.
