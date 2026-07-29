## MODIFIED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, heading?, bottomActions?, onTitleDoubleTap?)` (the optional actions slot carries a container-arranged cluster of action composables, centered across the width; `onTitleDoubleTap?` is an action callback like any other — a **hidden** operator affordance on the app-name label, capability `diagnostic-logging`, which the container wires as a raw pointer gesture exposing no click semantics and no ripple, since an affordance that reads as a control is not hidden; `null` wires no gesture at all), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Error`, `Waiting`, `Photos`, `Loading`, `InProgress`, `Complete`), `PrimaryButton(label, onClick)`, a borderless `SecondaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Both dialogs carry an **optional `body`** — a plain string of explanatory text rendered beneath the title in the iOS-alert anatomy — because a bare title cannot always state a consequence (a switch's participation reset, a leave's effect); `body?` carries text only and no appearance. Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it (the switch-section, minor-section, checkmark-row, event-hero, cutoff-choice, error-banner, and join-gate components added by this change are specified in their own requirements below).

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: The confirmation dialog carries an optional body
- **WHEN** a screen raises a confirmation that needs to state a consequence
- **THEN** it passes a `body` string alongside the title and confirm/cancel labels, and the skin renders it beneath the title with no appearance parameter crossing the signature

#### Scenario: The hidden title gesture exposes no control
- **WHEN** a screen supplies `onTitleDoubleTap` and the rendered layout's accessibility tree and controls are inspected
- **THEN** the app-name label exposes no click action and no control affordance, so the gesture is discoverable only to someone told about it

#### Scenario: Progress is expressed as meaning, not styling
- **WHEN** a screen displays an in-progress pass
- **THEN** it passes only `StatusIndicator.InProgress` to `StatusHero`, and the skin alone determines the visual form — the screen never says how far along, because the status surface reports a mood, not a number (capability `sync-status-screen`)

#### Scenario: The primary action is semantic
- **WHEN** a screen renders its main call to action
- **THEN** it passes only a label and an `onClick` callback to `PrimaryButton`, and the skin alone determines the visual form
