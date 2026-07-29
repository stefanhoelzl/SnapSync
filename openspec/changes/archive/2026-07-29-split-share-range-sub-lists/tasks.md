## 1. Component: two captioned wells

- [x] 1.1 In `AppCutoffSection.kt`, restructure `AppRangePresetChoices` to emit two groups — From then Until — each as `caption + AppSubSection { rows }`, replacing the single `Column` of caption/rows/caption/rows. Signature unchanged; the two pickers, their seeding, and their coerce-into-window confirms stay exactly as they are.
- [x] 1.2 Move the group caption out of the well: render it above its well at the card's own text inset (the one `AppSectionNote` / `AppSectionValue` use), one type level quieter than today, in the same muted role colour. No new colour or type token.
- [x] 1.3 Mark each caption as an accessibility heading.
- [x] 1.4 Tag each well (`from-group` / `until-group`) so the grouping is addressable in tests.
- [x] 1.5 Update the `AppRangePresetChoices` KDoc: two grouped sub-lists it owns the wells for, captions above each well, still embedded in the Share section's card (no card of its own).

## 2. Call sites

- [x] 2.1 In `StatusScreen.kt` `ReadyLayout` (join surface), drop the `AppSubSection { … }` wrapper around `AppRangePresetChoices`; keep every argument as-is.
- [x] 2.2 In `StatusScreen.kt` `ReconfigureScreen`, do the same — both surfaces change together (`reconfigure-membership`: one decision surface).
- [x] 2.3 Remove the now-unused `AppSubSection` import from `:ui:screens` and update the comment in `ReadyLayout` that describes the rows sitting "in the section's recessed well".

## 3. Design-system docs

- [x] 3.1 Update `AppSubSection`'s KDoc in `AppToggleSection.kt`: it recesses *a group* of a section's second-level rows, and a section may compose more than one well without adding a level. Keep it public — `design-system` names it a required building block.
- [x] 3.2 Check the `AppSubSection` reference in `AppSummaryCard.kt`'s divider comment still reads true.

## 4. Tests

- [x] 4.1 Extend `AppRangePresetChoicesTest` with containment assertions: each caption and its own handle's rows are descendants of that handle's tagged group, and neither group contains the other handle's rows. Assert containment only — never the count of intermediate nodes.
- [x] 4.2 Confirm the existing row-tag, picker-commit, cancel, and disabled-`Now` assertions still pass unchanged (row tags are untouched by this change).
- [x] 4.3 Run `./gradlew build` (JVM tests + all targets) and `./gradlew compileIosMainKotlinMetadata`.

## 5. Visual verification

- [x] 5.1 Launch `./gradlew :test:harness-driver:driveForge`, click **Ready (confirm)**, capture `/phone.png`, and compare against the pre-change capture: the two groups read as separate lists, each caption reads as a heading rather than a disabled first row.
- [x] 5.2 Check the same in the dark theme (panel **Theme (phone pane)**), confirming both wells still recess against the card in both themes.
- [x] 5.3 Settle the vertical rhythm (caption spacing, gap between the wells) from the rendered result — the one open question in `design.md`.

## 6. Spec sync

- [x] 6.1 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [x] 6.2 Confirm the delta's two MODIFIED requirements still match the main spec's headers exactly, and that the removed lines in the diff are only the intended ones.
