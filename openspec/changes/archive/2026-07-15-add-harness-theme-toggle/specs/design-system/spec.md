## MODIFIED Requirements

### Requirement: Light and dark theme with a scannable QR in both

The Material 3 skin SHALL support both a light and a dark theme, applying the app's brand accent in
each. `AppTheme` SHALL select the theme from the platform light/dark setting by default; it SHALL also
honor a **test-only ambient override** (a `LocalDarkThemeOverride` CompositionLocal, default absent)
that forces light or dark, so a test harness can render either theme deterministically. The override
SHALL default to absent, in which case `AppTheme` follows the system setting exactly as if it did not
exist — no product surface provides it. The override is a CompositionLocal, not an `App*` parameter:
the theme choice SHALL NOT introduce appearance parameters on `App*` signatures. The QR component SHALL
render **dark modules on a light card in both themes** — the design system SHALL NOT render an inverted
(light-on-dark) QR, which does not scan reliably. Screens remain written against `App*` only.

#### Scenario: Dark theme keeps the QR dark-on-light
- **WHEN** the app renders in its dark theme and shows the join QR
- **THEN** the QR is dark modules on a light card (not inverted), remaining scannable

#### Scenario: Theme adds no appearance parameters
- **WHEN** a screen renders any `App*` component under either theme
- **THEN** the component's signature carries no `Modifier`, color, shape, or text-style parameter

#### Scenario: Absent override follows the system setting
- **WHEN** no `LocalDarkThemeOverride` is provided in the composition
- **THEN** `AppTheme` selects its theme from the platform light/dark setting, unchanged from having no override

#### Scenario: Provided override forces the theme
- **WHEN** `LocalDarkThemeOverride` is provided as dark (or light) around a composition
- **THEN** `AppTheme` renders the dark (or light) color scheme regardless of the platform setting
