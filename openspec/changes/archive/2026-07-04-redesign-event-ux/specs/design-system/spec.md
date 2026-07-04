## ADDED Requirements

### Requirement: App status-line component

The design system SHALL provide a semantic status-line component that renders the joined-layer sync
health from a single sealed semantic value (e.g. `InSync` / `Syncing(uploadArrow, downloadArrow)` /
`NeedsAccess`), where each arrow state is one of `Hidden` / `Static` / `Pulsing`. Per the
semantic-only rule it SHALL expose **no** appearance parameters (no `Modifier`, color, shape, or text
style) — callers pass only the health value and, for the attention state, an `onClick`. The component
SHALL animate a `Pulsing` arrow and render a `Static` arrow without motion, SHALL render the attention
(`NeedsAccess`) state as the **only** variant carrying a background, and SHALL respect reduced-motion
preferences. It SHALL surface **no numeric counts**.

#### Scenario: Health value drives the rendering
- **WHEN** the status line is given `Syncing(upload = Pulsing, download = Hidden)`
- **THEN** it shows the upload arrow animating, no download arrow, and the "Syncing…" label, with no
  counts and no exposed appearance parameters

#### Scenario: Only the attention state has a background
- **WHEN** the status line renders `InSync` or `Syncing`
- **THEN** it is flat (no background); **WHEN** it renders `NeedsAccess`, it carries a background and
  invokes `onClick` on tap

### Requirement: Flat icon action buttons

The design system SHALL provide flat icon-only action components for the joined-layer share and leave
actions — no container background in the resting state, only the semantic glyph. They SHALL follow the
semantic-only rule (a description/label and an `onClick`, no appearance parameters), keeping the
underlying icon glyphs contained in the components module.

#### Scenario: Icon actions are flat
- **WHEN** the share or leave icon action renders in its resting state
- **THEN** it shows only its glyph with no container background, and exposes no appearance parameters

### Requirement: Light and dark theme with a scannable QR in both

The Material 3 skin SHALL support both a light and a dark theme, applying the app's brand accent in
each. The QR component SHALL render **dark modules on a light card in both themes** — the design
system SHALL NOT render an inverted (light-on-dark) QR, which does not scan reliably. Screens remain
written against `App*` only; the theme choice SHALL NOT introduce appearance parameters on `App*`
signatures.

#### Scenario: Dark theme keeps the QR dark-on-light
- **WHEN** the app renders in its dark theme and shows the join QR
- **THEN** the QR is dark modules on a light card (not inverted), remaining scannable

#### Scenario: Theme adds no appearance parameters
- **WHEN** a screen renders any `App*` component under either theme
- **THEN** the component's signature carries no `Modifier`, color, shape, or text-style parameter
