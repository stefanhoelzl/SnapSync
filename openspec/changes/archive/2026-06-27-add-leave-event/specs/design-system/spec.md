## MODIFIED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, bottomEndAction?)` (the optional slot carries a single bottom-right action composable), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Warning`, `Error`, `Waiting`, `Photos`, `Progress(fraction)`), `PrimaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), and `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)`. Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters. The inventory grows demand-driven with the screens that need it.

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

#### Scenario: The leave action is a flat icon component with no appearance params
- **WHEN** a screen renders the leave action
- **THEN** it passes only an accessibility label and an `onClick` callback; the flat, icon-only treatment and the Logout glyph are the skin's choice, with no color, Modifier, or Material 3 type in the signature

#### Scenario: The confirmation dialog is semantic
- **WHEN** a screen raises a confirmation
- **THEN** it passes only title text, confirm/cancel labels, and `onConfirm`/`onDismiss` callbacks to `AppConfirmDialog`, and the skin alone determines the dialog's visual form

### Requirement: Material 3 containment

Within the product UI, only the design-system components module SHALL depend on or import Material 3 — including the Material icon artifact (`compose.materialIconsExtended`), which is used solely inside the components module to render glyphs (e.g. the leave action's `Icons.AutoMirrored.Filled.Logout`); the `Icons.*` import SHALL NOT appear in any screen module or in any `App*` signature. Screens are composed exclusively of `App*` components plus meaning-free layout primitives (e.g. `Column`, `Spacer`), so a future skin (e.g. Cupertino) is a components-module change only. The desktop harness's control panel is exempt: it is test equipment and deliberately uses raw Material 3, never `App*` components (asymmetric investment).

#### Scenario: Material 3 is contained
- **WHEN** module dependencies and imports are inspected
- **THEN** Material 3 — and the Material icon artifact — appears only in the design-system components module and the desktop harness's control-panel code, never in screen modules

#### Scenario: Icon glyphs do not leak into screens
- **WHEN** the leave action's glyph is rendered
- **THEN** the `Icons.*` reference lives in the components module's skin, and the screen passes only the semantic leave component

### Requirement: Semantic containers own convention-bearing arrangement

Where platform conventions hold opinions about arrangement (screen insets, title placement, the status screen's centered hero, bottom-anchored screen actions — later: action ordering/stacking, grouped lists), screens SHALL express the arrangement through semantic slotted containers rather than raw geometry, so a skin can re-arrange without touching screens. `ScreenLayout(title, bottomEndAction?) { content }` owns the screen's edge insets, title placement, the vertical centering of the body content, and the placement of an optional bottom-right action (the screen supplies the action composable; the container owns where it sits). `StatusHero` owns the hero's internal arrangement (indicator inline-left of the headline, muted detail line beneath) and its typographic hierarchy. Raw layout primitives remain permitted only for meaning-free geometry no platform convention covers.

#### Scenario: Screen structure goes through the container
- **WHEN** the status screen is composed
- **THEN** its title, edge insets, and body centering come from `ScreenLayout`, and the screen body contains no hardcoded screen-level inset, title placement, or centering

#### Scenario: Bottom action placement goes through the container
- **WHEN** the status screen renders its leave action in the joined layer
- **THEN** the action's bottom-right placement comes from `ScreenLayout`'s slot, and the screen hardcodes no bottom-anchor geometry

#### Scenario: Hero arrangement goes through the component
- **WHEN** a screen renders a status hero
- **THEN** the icon/headline/detail arrangement, spacing, and the muted detail emphasis come from `StatusHero`, not from the screen
