## Context

The complexity tiers (capability `complexity-budgets`, decision record
`changes/archive/2026-08-27-add-repo-wide-complexity-gates`) landed seeded at what the tree measured.
Ranked by how far each tier's ceilings sit above detekt's own defaults, `ui` is the loosest shipped
scope after `core`:

| rule | ui | detekt default |
|---|---|---|
| CyclomaticComplexMethod | 36 | 15 |
| CognitiveComplexMethod | 46 | 15 |
| LongMethod | 169 | 60 |
| LongParameterList | 30 | 6/7 |
| TooManyFunctions | 17 | 11 |
| MaxLineLength | 133 | 120 |
| ComplexCondition · NestedBlockDepth · LargeClass · ReturnCount | at default | — |

plus four rules on the register: `MagicNumber` (110), `MayBeConst` (1), `TopLevelPropertyNaming` (1),
`MatchingDeclarationName` (1). Running the tier at pure defaults reports **160 findings**.

Five measurements shaped this design, each of which contradicted a first guess:

1. **`ignorePropertyDeclaration` defaults to `false`.** Naming a literal creates a property
   declaration, which the rule flags. So a naming pass does not reduce `MagicNumber` — it relocates
   it. With the knob on, `ui` drops 110 → 74 and `core` 30 → 25 without a line of code changing,
   because `private val LightColors = lightColorScheme(…)` is a single property declaration and the
   whole palette initialiser drops out.
2. **The palette has real duplication.** `AppTheme.kt` holds 29 literals, 22 distinct: seven values
   appear twice across the light and dark schemes (`0xFFD9F0E6` is both `primaryContainer` and
   `secondaryContainer`, and so on). Naming them is worth doing on its own terms.
3. **`UserCommands` is not a drop-in for `StatusScreen`'s callbacks.** 18 callbacks against 11 fields,
   in a different vocabulary. `commitJoin` takes 9 arguments including `eventId` and `name`;
   `onConfirmJoin` takes 4, because the container supplies event identity from state. `onCancelJoin`,
   `onRetryLoad`, `onRenameStatusConsumed` and `onConfirmSwitch` have no domain counterpart — they are
   screen-local flow control. The container's adaptation is real work, and pushing it into the screen
   would make the screen know event ids and domain argument order.
4. **Compose Resources is not configured in this repo** — no `composeResources` directory, no
   `painterResource`. But `ImageVector` already is: `AppJoinGate.kt` uses `Icons.Outlined.LinkOff`.
   So "move the glyphs to vector assets" means either a new dependency in the one module that exists to
   withhold dependencies, or an `ImageVector.Builder` whose coordinates are still Kotlin literals the
   rule still flags.
5. **No tier is at pure defaults today.** `buildscripts` looked like it — every threshold is detekt's
   default — but deleting its config makes the tier report 9 findings and fail, because it also has two
   rules off. "At defaults" therefore has to mean *and an empty register*.

## Goals / Non-Goals

**Goals:**

- `:ui:components` and `:ui:screens` sit at the repo baseline on every rule they can reach, with an
  empty exclusion register, so `config/detekt/ui.yml` shrinks to the one rule that is out of reach.
- The repo gains a place for a *reading* of a rule that applies everywhere, stated once with its
  argument, distinct from a per-scope ceiling.
- The absence of a tier config becomes meaningful: it says the scope is at the baseline.
- Every number that falls, falls because the code got better — not because a rule was switched off.

**Non-Goals:**

- **Other tiers' ceilings.** `core`, `harness` and `tests` keep their numbers; only the baseline's
  effect on them is measured and left for their own changes.
- **Changing any `App*` signature.** `StatusHero(indicator, headline, detail?)` is unchanged; the glyph
  source is a skin choice `design-system` already grants.
- **A new dependency.** The Material icon artifact is already in `:ui:components`.
- **Redesigning the visual identity.** The glyph swap changes four icons to their Material equivalents,
  nothing else.

## Decisions

### D1 — A shared baseline layer, distinct from a per-scope ceiling

`config/detekt/_base.yml` is layered beneath every tier config
(`config.setFrom(files("_base.yml", "<tier>.yml"))` — verified: the base applies, the tier overrides).

This exists because two things that look alike are not. A **ceiling** is a number describing one
scope, seeded by measurement and ratcheted down. A **reading** is an interpretation of what a rule
means, and it cannot sensibly differ per scope: if a named constant has complied with `MagicNumber` in
`:ui:components`, it has complied in `:domain` too. Putting readings in each tier config would restate
the same argument eight times and invite the eight copies to drift.

The alternative — `ignorePropertyDeclaration` in `ui.yml` alone — was rejected twice over: it makes the
same literal a violation in one module and not another with no explanation, and it makes `ui`'s config
undeletable, which defeats D2.

Only readings belong here. A number does not.

### D2 — A tier config exists only where the scope deviates from the baseline

The tier config becomes optional; its absence means "at the baseline". The coverage guard flips from
*every tier has a config* to *every config belongs to a tier*.

The invariant this buys is self-documenting: **the set of files under `config/detekt/` is the list of
scopes still carrying debt.** Creating one is the visible act of admitting a regression, which is a
stronger signal than editing a number inside a file that already exists.

Considered and rejected: keeping a header-only `ui.yml` carrying "this scope reached the baseline and
may not regress". It is not wrong, but it makes the reader open a file to learn it is empty, and the
statement it carries is already implied by the file's absence once the invariant is stated. Also
rejected: one shared `defaults.yml` that several tiers point at — the guard's one-config-per-task
assertion would have to be relaxed, and the tiers would stop being independently ratchetable.

The prose contract that governs ceilings (capability `complexity-budgets`, "A ceiling may only fall")
is unaffected: it lives in the file that carries the numbers it governs, and a scope with no numbers
has no ceiling to protect.

### D3 — The naming pass, and what it is actually for

Four files, and the reason differs in each:

- **`AppTheme.kt`** — 22 named colours replacing 29 literals. The point is the seven duplicates, not
  the lint: today, changing `primaryContainer` and forgetting `secondaryContainer` is silent.
- **`AppStatusLine.kt`** — its four amber `Color(0x…)` values move into the palette. The design system
  currently defines brand-adjacent colours in two places.
- **`AppDateTimeField.kt`** — `(leadingBlanks + daysInMonth + 6) / 7`, `for (col in 0 until 7)`,
  `row * 7 + col`. That `7` is days-per-week and reads as arithmetic noise today.
- **`AppMark.kt`** — `size.minDimension / 100f`, `48f * s`, `cardPath(14f, 14f, 11f)`,
  `-11.0 * PI / 180`. The mark is authored on an implicit 100-unit grid; naming makes the grid
  explicit and `cardPath` readable.

D1 is what makes this work rather than merely move findings around. Without it the pass would trade
110 findings for roughly 28 property declarations and be, on the rule's own terms, no progress.

### D4 — The glyphs become Material icons, not vector assets

`Icons.Outlined.Check`, `Close`, `Schedule`, `PhotoLibrary` replace ~30 hand-drawn path coordinates in
`StatusHero.kt`. No new dependency: the Material icon artifact is already in `:ui:components`, and
`design-system` states both that a component's glyph is *"the skin's choice, not passed in"* and that
`compose.materialIconsExtended` is *"used solely inside the components module to render glyphs"*. This
is the freedom that spec already grants, exercised.

Rejected: **Compose Resources with SVGs** — a new dependency in the module whose stated purpose is
withholding dependencies by compile error, which is a `module-architecture` decision and not something a
lint target should force. Rejected: **`ImageVector.Builder` in Kotlin** — no new dependency, but the
coordinates remain Kotlin literals and the rule still flags every one, so it solves nothing. Rejected:
**suppressing the draw functions** — it works, but it is the only route of the four that leaves the code
unchanged, and the icons are standard shapes with standard equivalents.

The cost is honest and is why the screenshots ride along (D6): the rendered pixels change.

### D5 — A screen-level `StatusActions`, not `model/UserCommands`

`StatusScreen` drops from 26 parameters to roughly 9: `state`, seven data parameters, and one
`actions: StatusActions = StatusActions()`. Every field of the bundle is defaulted exactly as the
parameters are today, so each host keeps its per-callback opt-in — the three production call sites pass
only 10–11 arguments precisely because the defaults let them wire what they need.

The bundle is declared in `:ui:screens` and holds the screen's vocabulary. Measurement 3 above is the
argument: the domain's commands and the screen's callbacks are different shapes, and the container is
where one becomes the other. Reusing `UserCommands` would relocate that translation into the screen.

`:ui:screens` already imports eight `model/` types and no guard constrains it, so the layering objection
that might have justified the loose callbacks does not exist.

### D6 — Decomposition splits files, and the screenshots ride along

The four oversized composables — `StatusScreen` (138 statements, cyclomatic 30, cognitive 45),
`JoiningEventScreen` (168, 35), `ReconfigureScreen` (148, 28, 44), `ReadyLayout` (106, cognitive 23) —
are decomposed along the seams they already have. Because that adds functions to files already over
`TooManyFunctions`, `StatusScreen.kt` (1489 lines, four distinct screens) splits per screen and
`AppDateTimeField.kt` (887 lines) splits per widget. The file split is not a lint workaround: a single
file holding four screens is one file too many at any threshold.

**The screenshots do not move, and that was measured rather than assumed.** The design originally had
the six raws re-captured in this change, on the reasoning that the glyphs render inside `StatusHero`
on every captured state. That reasoning was wrong: `AppStatusLine` renders the check, clock and
warning that appear in the captured states, it draws its own `Icons.Filled.*` directly, and it never
calls `IndicatorIcon`. The swapped glyphs are reachable from exactly two places — `StatusHero`, called
once with `Loading` (a progress spinner, not swapped), and `AppErrorBanner`, which no captured state
shows because none of `create`/`joining`/`in_sync` is a failure state.

Measured both ways: the swap changes **348 pixels, 0.11% of a 390x844 frame**, all inside one 26x26 box
at the indicator — and the committed `in_sync-light.png` contains no error banner and no hero at all.
So no re-capture is performed. Had one been, it would have committed six byte-identical files, and
CLAUDE.md's rule that a `joining`/`in_sync` diff means the UI genuinely moved would have made any diff
an alarm rather than the expected outcome.

That same pixel diff is the strongest evidence for D3: `AppTheme`, `AppMark`, `AppToggleSection` and
`AppDateTimeField` all render in the frame that was compared, and every one of them contributed ZERO
differing pixels. The naming pass is value-preserving by measurement, not by inspection.

### D7 — The three one-liners are fixed, not registered

`MayBeConst` (`AppStatusLine.kt:107`), `TopLevelPropertyNaming` (`AppDateTimeField.kt:699`) and
`MatchingDeclarationName` (`StatusHero.kt:31`) are each a single-line fix. Registering them would leave
`ui` with a non-empty register, and measurement 5 established that "at the baseline" has to mean an
empty register — otherwise `buildscripts`, whose thresholds are all detekt's defaults, would already
qualify while failing the moment its config is removed.

## Risks / Trade-offs

- **[One change carrying a pixel change, a cross-module signature change and a 1489-line file split]** →
  Verified through `:test:harness-driver`, which composes the shipped harness root offscreen so every UI
  state can be clicked and read back as real pixels and a semantics tree, before and after. That harness
  exists for exactly this. Plus `./gradlew build` and the screenshot eyeball pass. The signature change
  is the least risky part: all six call sites are in-tree and the compiler names every one.
- **[The glyph swap loses a bespoke visual]** → The four glyphs share stroke width and circle framing
  with the LED dot via `GlyphScope`. If the Material equivalents read worse in the forge harness, D4 is
  the decision to revisit — the fallback is suppressing the draw functions, at the cost of `ui` keeping
  one register entry.
- **[A screenshot run picks up a system notification]** → A *"Ready for Apple Intelligence"* banner hit
  1 of 2 runs historically. The raws are eyeballed before committing and the run re-dispatched if one
  lands; this is not automatable, because `in_sync` legitimately renders the event name in the top band.
- **[`_base.yml` becomes a dumping ground]** → It holds *readings*, never numbers, and D1 states the
  distinction. A ceiling appearing there would make one scope's measurement everyone's contract.
- **[The absence of a config file is silent]** → It is, and that is the point — but only because the
  coverage guard independently asserts every subproject resolves to exactly one tier. Without that
  guard, "no file" and "no tier" would be indistinguishable.

## Migration Plan

One change, one PR:

1. `_base.yml`, the optional tier config, and the coverage-guard flip — no code change, all tiers still
   green.
2. The naming pass across the four files.
3. The glyph swap, then the screenshot re-capture and eyeball.
4. `StatusActions` and its three shell call sites.
5. The decomposition and the two file splits.
6. The three one-liners, then `config/detekt/ui.yml` shrinks to `LongParameterList` alone (D8).

Rollback is per-step; the baseline layer is additive and no other tier's numbers move, so reverting the
`ui` work leaves the mechanism in place and vice versa.

### D8 — Three rules stay in `ui.yml`, and the reason is that they trade against each other (found during apply)

Seven of the ten rules reach the baseline. Three do not, and the obstruction is not effort — each was
worked, and working one raised another:

- **`LongParameterList` is fixed by bundling, and a bundle is a constructor.** `StatusActions` was
  created BY THIS CHANGE to fix this rule on `StatusScreen` (26 parameters to 9); detekt counts its own
  18 fields as a long parameter list. The recursion has no base case. Fourteen of the remaining sites
  are leaf design-system components at 6-8 parameters, where Compose's own APIs routinely exceed six.
- **`LongMethod` is fixed by extracting composables, and each extraction is a new function with a new
  parameter list.** Measured on `StatusScreen`: two genuine extractions (`StatusOverlays`,
  `JoinedBottomActions`) took it from 138 statements to 71 and cyclomatic 30 to 18 — and over those same
  two steps the TIER's totals ROSE, `LongMethod` 10 to 11 and `LongParameterList` 20 to 21.
- **`CyclomaticComplexMethod` was brought most of the way and then stopped.** Four changes that stand on
  their own merits — grouping the overlays, naming the joined action cluster, lifting the join gate's
  pure derivation into `JoinSelection.kt`, and deduplicating the range-resolution rules that
  `ReconfigureScreen` had been spelling out a second time — took `JoiningEventScreen` 35 to 16,
  `ReconfigureScreen` 28 to 17, `StatusScreen` 30 to 18. The residue is 1-3 points per screen, and
  buying it means inventing functions to hold fragments of a `when` dispatch.

So `ui.yml` survives holding exactly these three at their measured values, each tight (verified: every
one fails at its value minus one). That is weaker than the file's deletion and is stated as one. The
alternative offered and declined was to make the exemptions baseline READINGS, which would have deleted
the file at the cost of switching three rules off repo-wide.

The finding worth carrying beyond this change: **for Compose, these four structural rules cannot be
satisfied simultaneously**, because the remedy for each is the cause of another. `TooManyFunctions` is
the fourth — it reappeared twice during the decomposition and was cleared only by splitting files.

## Open Questions

- Whether `core`'s five baseline-freed `MagicNumber` findings let its register entry shrink. Measured
  but out of scope here; it belongs to whatever change brings `core` down.
- **Five of the seven `StatusIndicator` cases are dead.** `Success`, `Waiting`, `Photos`, `InProgress`
  and `Complete` are declared in the sealed interface and constructed by nothing: `StatusHero` is called
  once (with `Loading`) and `IndicatorIcon` once (with `Error`), and no harness, test, `:ui:presentation`
  or `:domain` source names the type at all. Three of them were re-pointed at Material icons by D4 for a
  lint target that nothing renders. Deleting them is not in scope here — `design-system`'s requirement
  enumerates all seven cases by name, so it needs a delta of its own.
- Whether `buildscripts` should follow `ui` to the baseline — its two register entries are
  `UnusedPrivateProperty` (a Gradle DSL false positive that no code change can fix) and `SpreadOperator`,
  so it may be the one tier that cannot reach an empty register.
