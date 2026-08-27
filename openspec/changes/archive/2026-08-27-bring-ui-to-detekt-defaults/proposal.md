## Why

The complexity tiers landed green at what the tree measured, and the `ui` tier was seeded loosest of
the shipped scopes: cyclomatic 36 against detekt's default of 15, cognitive 46, `LongMethod` 169,
parameters 30, plus four rules switched off. Those numbers are a description of `:ui:components` and
`:ui:screens` as they stand, not a standard anyone argued for. `ui` is the first tier to be brought
down, and the target is the strongest one available: **detekt's own defaults, with an empty exclusion
register**.

Measured against the tree, that is **160 findings**. Investigating them turned up four things that
change what the work is:

- **110 are `MagicNumber`, and naming them does not remove them.** detekt's `ignorePropertyDeclaration`
  defaults to `false`, so `private val GreenLight = Color(0xFF0E9D6B)` is flagged *because* it is a
  named token. Without a change to how that rule is read, a naming pass relocates findings rather than
  resolving them.
- **The palette is worth naming anyway.** `AppTheme.kt` holds 29 colour literals of which only 22 are
  distinct — 7 are duplicated across the light and dark schemes, where changing one and forgetting its
  twin is silent.
- **`StatusScreen`'s 26 parameters are not an unused-bundle mistake.** Its 18 callbacks are a screen
  vocabulary, not the domain's: `UserCommands.commitJoin` takes 9 arguments including `eventId`, while
  the screen's `onConfirmJoin` takes 4 because the container supplies identity from state, and
  `onCancelJoin`/`onRetryLoad`/`onRenameStatusConsumed` have no domain counterpart at all.
- **Decomposition makes one finding worse.** `StatusScreen.kt` (1489 lines) and `AppDateTimeField.kt`
  (887) are already over `TooManyFunctions`; splitting the four oversized composables into named
  sub-composables pushes both further over unless the files split too.

## What Changes

- **A shared `config/detekt/_base.yml`** that every tier task layers beneath its own config
  (`config.setFrom(base, tier)` — verified: the base applies and the tier overrides). It carries
  repo-wide *readings* of a rule, stated once with their argument — never a ceiling, which describes one
  scope. Two entries: `MagicNumber.ignorePropertyDeclaration: true` (a named constant has complied with
  the rule that exists to force naming) and `FunctionNaming.ignoreAnnotated: ['Composable']` with
  `excludes: []` (PascalCase composables are Compose's documented dialect; detekt's default `**/test/**`
  exclusion would otherwise switch the rule off for production source in this repo's `:test:*` MODULES).
  Measured effect: `ui` 160 → 38 findings, `core` 30 → 25 `MagicNumber`, every other tier unchanged and
  still green. It only relaxes, so no tier breaks.
- **A tier's own config becomes optional**, and its absence means "at the repo baseline". The set of
  tier config files becomes the list of scopes still carrying debt, and creating one is the visible act
  of admitting a regression.
- **`config/detekt/ui.yml` shrinks from ten rules to three** — `CyclomaticComplexMethod` 19,
  `LongMethod` 164, `LongParameterList` 30 — each tight and each with its reason at it. The other seven
  reach the baseline and leave the file. The three that remain trade against one another: bundling
  parameters creates constructors, extracting composables creates parameter lists, and the tier's totals
  rose over two otherwise-good extractions.
- **The coverage guard flips** from *every tier has a config* to *every config belongs to a tier*.
- **`:ui:components` and `:ui:screens` reach the baseline on seven of ten rules**, with an empty
  register (no rule is switched off anywhere):
  - the palette is named, the amber colours `AppStatusLine` defines on its own move into it,
    `AppDateTimeField`'s calendar arithmetic names days-per-week, and `AppMark`'s implicit 100-unit
    authoring grid becomes explicit;
  - `StatusHero`'s four hand-drawn glyphs (check, cross, clock, photo) become Material icons from the
    artifact the components module already uses, removing ~30 path coordinates;
  - a **`StatusActions`** bundle replaces `StatusScreen`'s 18 loose callbacks, every field defaulted as
    today so each host keeps its per-callback opt-in;
  - `StatusScreen`, `JoiningEventScreen`, `ReconfigureScreen` and `ReadyLayout` are decomposed, and
    `StatusScreen.kt` splits per screen while `AppDateTimeField.kt` splits per widget;
  - `MayBeConst`, `TopLevelPropertyNaming`, `MatchingDeclarationName` and both `MaxLineLength` sites are
    **fixed, not registered** — `MagicNumber` reaches zero, and `StatusIndicator` moves to its own file.
- **The screenshots are NOT re-captured**, which was measured rather than assumed: `AppStatusLine`
  renders the check, clock and warning seen in `create`/`joining`/`in_sync` and draws its own icons
  without ever calling `IndicatorIcon`. The swapped glyphs reach only `StatusHero` (called once, with the
  unswapped `Loading` spinner) and `AppErrorBanner`, which no captured state shows.
- **Not changed**: `StatusHero(indicator, headline, detail?)`'s signature, and no other tier's numbers.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `complexity-budgets`: a scope no longer requires its own configuration file — a shared baseline layer
  carries repo-wide readings of a rule, a tier config exists only where a scope deviates further, and
  the coverage guard asserts every config belongs to a tier rather than every tier having a config.

## Impact

- **Build**: `config/detekt/_base.yml` added and layered by `registerDetektTier`; the tier config becomes
  optional; `config/detekt/ui.yml` shrinks from ten rules to one.
- **Guards**: `DetektTierCoverageTest`'s config assertions invert.
- **`:ui:components`**: `AppTheme`, `AppStatusLine`, `AppMark`, `AppDateTimeField` (split), `StatusHero`
  (glyph source changed). No `App*` signature changes — `design-system` already states that a
  component's glyph is the skin's choice and that the Material icon artifact belongs in this module.
- **`:ui:screens`**: `StatusScreen`'s signature (26 params → ~9) and a new `StatusActions` type;
  `StatusScreen.kt` splits per screen. All six call sites are in this repo — three shells
  (`MainViewController`, `ForgeViewController`, `StatusPane`) and three test files — and `:ui:screens`
  is consumed only via `implementation(project(…))` and reaches no exported framework surface.
- **`screenshots/`**: untouched. The glyph swap changes 348 pixels (0.11%) of a 390x844 frame, all inside
  one 26x26 box at an indicator that no captured state renders.
- **Dependencies**: none added.
- **Other tiers**: unchanged numbers; `core` gains 5 fewer `MagicNumber` findings from the baseline,
  which its register already absorbs.
