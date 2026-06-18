## ADDED Requirements

### Requirement: SetupCard semantic container

The design-system SHALL provide a `SetupCard` semantic container that the setup gate composes its
steps from. `SetupCard(indicator, title, detail?) { actionSlot }` SHALL expose data-and-meaning
parameters only — a sealed `StatusIndicator` glyph, a `title` string, an optional `detail` string,
and an optional trailing action slot (filled by an `App*` action component such as `PrimaryButton`).
It MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) or a `Modifier`
parameter, and no Material 3 type may appear in its signature; the Material 3 card containment lives
inside the component. `SetupCard` SHALL own its internal arrangement (glyph inline-left of the title,
optional detail beneath, optional action beneath) and SHALL render compactly when no detail and no
action are supplied (a satisfied, collapsed step). If a neutral "pending step" glyph is required, the
sealed `StatusIndicator` inventory grows demand-driven per the existing convention.

#### Scenario: SetupCard signature is appearance-free
- **WHEN** the public signature of `SetupCard` is inspected
- **THEN** it carries only a `StatusIndicator`, a title, an optional detail, and an action slot — no
  colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Satisfied step renders compact
- **WHEN** a `SetupCard` is given a Success indicator and a title but no detail and no action
- **THEN** it renders as a compact glyph-plus-title row, with the card containment supplied by the
  component, not the screen

#### Scenario: Pending step renders detail and action
- **WHEN** a `SetupCard` is given a title, a detail, and a `PrimaryButton` in its action slot
- **THEN** it renders the glyph, title, detail, and action with arrangement owned by `SetupCard`
