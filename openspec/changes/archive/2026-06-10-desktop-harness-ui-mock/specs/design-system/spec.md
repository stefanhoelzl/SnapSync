# design-system Delta Specification

## ADDED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, counts, closed roles. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any `App*` signature. Initial inventory (this change): `AppTheme`, `ScreenLayout(title)`, `StatusText(text)`, `UploadProgress(done, total)`; the inventory grows demand-driven with the screens that need it.

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: Progress is expressed as meaning, not styling
- **WHEN** a screen displays upload progress of 3 completed out of 10
- **THEN** it passes only `done = 3, total = 10` to `UploadProgress`, and the skin alone determines the visual form

### Requirement: Material 3 containment

Within the product UI, only the design-system components module SHALL depend on or import Material 3. Screens are composed exclusively of `App*` components plus meaning-free layout primitives (e.g. `Column`, `Spacer`), so a future skin (e.g. Cupertino) is a components-module change only. The desktop harness's control panel is exempt: it is test equipment and deliberately uses raw Material 3, never `App*` components (asymmetric investment).

#### Scenario: Material 3 is contained
- **WHEN** module dependencies and imports are inspected
- **THEN** Material 3 appears only in the design-system components module and the desktop harness's control-panel code, never in screen modules

### Requirement: Semantic containers own convention-bearing arrangement

Where platform conventions hold opinions about arrangement (screen insets, title placement — later: action ordering/stacking, grouped lists), screens SHALL express the arrangement through semantic slotted containers rather than raw geometry, so a skin can re-arrange without touching screens. In this change, `ScreenLayout(title) { content }` owns the screen's edge insets and title placement. Raw layout primitives remain permitted only for meaning-free geometry no platform convention covers.

#### Scenario: Screen structure goes through the container
- **WHEN** the status screen is composed
- **THEN** its title and edge insets come from `ScreenLayout`, and the screen body contains no hardcoded screen-level inset or title placement
