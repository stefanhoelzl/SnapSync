## MODIFIED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, bottomEndActions?)` (the optional slot carries a container-arranged cluster of bottom-right action composables), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Warning`, `Error`, `Waiting`, `Photos`, `Progress(fraction)`), `PrimaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it.

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

#### Scenario: The share action is a flat icon component with no appearance params
- **WHEN** a screen renders the share action
- **THEN** it passes only an accessibility label and an `onClick` callback; the flat, icon-only treatment and the share glyph are the skin's choice, with no color, Modifier, or Material 3 type in the signature

#### Scenario: The QR component is semantic
- **WHEN** a screen renders a QR
- **THEN** it passes only the `content` string and an optional caption text to `AppQrCode`; the QR module pattern, quiet zone, sizing, and any styling are the skin's choice, with no color, Modifier, or Material 3 type in the signature

#### Scenario: The confirmation dialog is semantic
- **WHEN** a screen raises a confirmation
- **THEN** it passes only title text, confirm/cancel labels, and `onConfirm`/`onDismiss` callbacks to `AppConfirmDialog`, and the skin alone determines the dialog's visual form

#### Scenario: The destructive confirmation dialog is semantic and chosen by component, not flag
- **WHEN** a screen raises a confirmation whose confirm action is destructive
- **THEN** it picks `AppDestructiveConfirmDialog` — whose signature is identical to `AppConfirmDialog` (title, confirm/cancel labels, `onConfirm`/`onDismiss`) — passing no appearance parameter and no destructiveness flag; the destructive treatment is the skin's

#### Scenario: The destructive confirm button is filled with the error accent
- **WHEN** the skin renders `AppDestructiveConfirmDialog`
- **THEN** its confirm button is filled with the **error** accent (the skin's `colorScheme.error` role) while the cancel button stays the outlined secondary, and this color mapping appears on no `App*` signature

### Requirement: Flat icon action buttons

The design system SHALL provide flat icon-only action components for the joined-layer share and leave
actions — no container background in the resting state, only the semantic glyph. They SHALL follow the
semantic-only rule (a description/label and an `onClick`, no appearance parameters), keeping the
underlying icon glyphs contained in the components module. Because leaving an event is destructive, the
**leave** action's glyph SHALL be rendered in the **error** accent (the skin's `colorScheme.error` role)
while the share action keeps the default content tint; this is a skin-local color choice that SHALL NOT
introduce any appearance parameter on the component's signature.

#### Scenario: Icon actions are flat
- **WHEN** the share or leave icon action renders in its resting state
- **THEN** it shows only its glyph with no container background, and exposes no appearance parameters

#### Scenario: The leave icon is tinted with the error accent
- **WHEN** the leave icon action renders
- **THEN** its glyph is tinted with the error accent (marking the destructive action), it remains flat with no container background, and its signature carries no color, Modifier, or Material 3 type
