## 1. Re-establish the deadness before deleting

- [x] 1.1 Re-run the construction-site count immediately before editing, so the deletion rests on the
      tree as it is rather than on a measurement taken earlier: exactly two sites construct a
      `StatusIndicator` (`AppErrorBanner.kt` → `Error`, `CreateEventScreen.kt` → `Loading`), and every
      other reference is a `when` branch in `IndicatorIcon`.
- [x] 1.2 Confirm zero references from tests, `:app:desktop`, `:app:ios:forge`, `:test:harness-driver`
      and `:test:rig` — a case driven only by a harness preset would be surface, not dead code.
- [x] 1.3 Confirm `IndicatorIcon`'s `when` still carries no `else`, since that is what makes the whole
      deletion compiler-checked rather than reviewed.

## 2. The vocabulary

- [x] 2.1 Replace the sealed interface in `StatusIndicator.kt` with
      `enum class StatusIndicator { Loading, Error }`.
- [x] 2.2 Rewrite the KDoc. Drop the broken `[Progress]` link and the claim that a case "even carries a
      payload" — no case does, and that reasoning belongs to `AppSyncStatus`. State instead why an enum
      is the right form here and where a payload-carrying variant axis lives if one is ever needed.
- [x] 2.3 Keep the per-case comments that still say something true (`Loading`'s "indeterminate spinner:
      work with no measurable progress"); drop the ones describing deleted cases.

## 3. The renderer

- [x] 3.1 Reduce `IndicatorIcon`'s `when` to the two live branches.
- [x] 3.2 Delete `LedDot` and `LedYellow` — both existed only to render `InProgress` and `Complete`.
- [x] 3.3 Remove the three `Icons.Outlined.*` imports that served only dead branches (`CheckCircle`,
      `Schedule`, `Image`), and any other import left unused (`Canvas` if `LedDot` was its last user).
- [x] 3.4 Confirm `StatusHero(indicator, headline, detail?)`'s signature is untouched.

## 4. Verify

- [x] 4.1 `./gradlew build` green. A deleted case that was still needed is a compile error here, which is
      the point of doing this while the `when` has no `else`.
- [x] 4.2 `./gradlew compileIosMainKotlinMetadata` green — `:ui:components` compiles for iOS too.
- [x] 4.3 Confirm the `ui` tier's detekt ceilings still pass and that none has become slack: the file
      shrinks, so `LongMethod`/`CyclomaticComplexMethod` may now be satisfiable at a lower number, and a
      ceiling that could fall should fall (capability `complexity-budgets`).
- [x] 4.4 Drive the two live states through the forge harness — the "Creating your event …" hero and an
      error-banner state — and confirm both still render.

## 5. The spec

- [x] 5.1 Apply the delta: the inventory names `Loading` and `Error` with the reason at it, and the
      scenario "Progress is expressed as meaning, not styling" is gone.
- [x] 5.2 Confirm the removal is not a silent loss: the principle is carried by the "App status-line
      component" requirement's "Pulsing arrow drives the ongoing label", which names a live component and
      symbols that exist.
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes.
