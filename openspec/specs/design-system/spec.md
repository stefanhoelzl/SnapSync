# design system Specification

## Purpose

The semantic `App*` component layer that screens compose from, containing all Material 3 styling so a future skin swap is a components-module change only.

## Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title)`, `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Warning`, `Error`, `Waiting`, `Photos`, `Progress(fraction)`), and `PrimaryButton(label, onClick)` — the first action component; `SecondaryButton` arrives only with its first caller. The inventory grows demand-driven with the screens that need it.

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: Progress is expressed as meaning, not styling
- **WHEN** a screen displays an in-progress pass roughly 35% through
- **THEN** it passes only `StatusIndicator.Progress(fraction = 0.35f)` to `StatusHero`, and the skin alone determines the visual form

#### Scenario: The primary action is semantic
- **WHEN** a screen renders its main call to action
- **THEN** it passes only a label and an `onClick` callback to `PrimaryButton`, and the skin alone determines the visual form

#### Scenario: A neutral ask is not styled as a fault
- **WHEN** the permission ask renders its hero
- **THEN** it passes `StatusIndicator.Photos`, which the skin renders as a neutral photo-library glyph (not a warning or error treatment)

### Requirement: Material 3 containment

Within the product UI, only the design-system components module SHALL depend on or import Material 3. Screens are composed exclusively of `App*` components plus meaning-free layout primitives (e.g. `Column`, `Spacer`), so a future skin (e.g. Cupertino) is a components-module change only. The desktop harness's control panel is exempt: it is test equipment and deliberately uses raw Material 3, never `App*` components (asymmetric investment).

#### Scenario: Material 3 is contained
- **WHEN** module dependencies and imports are inspected
- **THEN** Material 3 appears only in the design-system components module and the desktop harness's control-panel code, never in screen modules

### Requirement: Semantic containers own convention-bearing arrangement

Where platform conventions hold opinions about arrangement (screen insets, title placement, the status screen's centered hero — later: action ordering/stacking, grouped lists), screens SHALL express the arrangement through semantic slotted containers rather than raw geometry, so a skin can re-arrange without touching screens. `ScreenLayout(title) { content }` owns the screen's edge insets, title placement, and the vertical centering of the body content. `StatusHero` owns the hero's internal arrangement (indicator inline-left of the headline, muted detail line beneath) and its typographic hierarchy. Raw layout primitives remain permitted only for meaning-free geometry no platform convention covers.

#### Scenario: Screen structure goes through the container
- **WHEN** the status screen is composed
- **THEN** its title, edge insets, and body centering come from `ScreenLayout`, and the screen body contains no hardcoded screen-level inset, title placement, or centering

#### Scenario: Hero arrangement goes through the component
- **WHEN** a screen renders a status hero
- **THEN** the icon/headline/detail arrangement, spacing, and the muted detail emphasis come from `StatusHero`, not from the screen

### Requirement: Runtime-data variants use sealed semantic values

Variant axes that are design-time choices (a call site statically picks one, e.g. button emphasis) SHALL be distinct components (`PrimaryButton`, not `AppButton(role = ...)`). Variant axes driven by runtime data (the variant arrives from state, possibly carrying a payload) SHALL be sealed semantic value parameters (e.g. `StatusIndicator`, whose `Progress` variant carries a fraction). Enum- or value-shaped parameters whose meaning is appearance remain banned in both cases.

#### Scenario: Data-driven indicator is a sealed value
- **WHEN** the status screen branches on UI state to render the hero
- **THEN** it selects a `StatusIndicator` value (not a different component per state), and only the `Progress` variant carries data
