## MODIFIED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, heading?, bottomActions?, onTitleDoubleTap?, onEditHeading?)` (the optional actions slot carries a container-arranged cluster of action composables, centered across the width; `onEditHeading?` is an action callback like any other — an **edit affordance on the heading**, capability `event-rename`, which the container renders beside the heading text as a control with click semantics and an accessibility label chosen by the skin, `null` rendering no affordance at all; it is the deliberate opposite of `onTitleDoubleTap?`, which is hidden, and the two occupy different slots — the heading and the app-name label — so neither gesture can shadow the other; `onTitleDoubleTap?` is an action callback like any other — a **hidden** operator affordance on the app-name label, capability `diagnostic-logging`, which the container wires as a raw pointer gesture exposing no click semantics and no ripple, since an affordance that reads as a control is not hidden; `null` wires no gesture at all), `StatusHero(indicator, headline, detail?)` with `StatusIndicator` (`Loading`, `Error` — an enum rather than a sealed interface, because neither case carries a payload; it enumerated five further cases until each was found to be constructed by nothing), `PrimaryButton(label, onClick)`, a borderless `SecondaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Both dialogs carry an **optional `body`** — a plain string of explanatory text rendered beneath the title in the iOS-alert anatomy — because a bare title cannot always state a consequence (a switch's participation reset, a leave's effect); `body?` carries text only and no appearance. Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it (the switch-section, minor-section, checkmark-row, event-hero, cutoff-choice, error-banner, and join-gate components added by this change are specified in their own requirements below).

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: The confirmation dialog carries an optional body
- **WHEN** a screen raises a confirmation that needs to state a consequence
- **THEN** it passes a `body` string alongside the title and confirm/cancel labels, and the skin renders it beneath the title with no appearance parameter crossing the signature

#### Scenario: The hidden title gesture exposes no control
- **WHEN** a screen supplies `onTitleDoubleTap` and the rendered layout's accessibility tree and controls are inspected
- **THEN** the app-name label exposes no click action and no control affordance, so the gesture is discoverable only to someone told about it


#### Scenario: The primary action is semantic
- **WHEN** a screen renders its main call to action
- **THEN** it passes only a label and an `onClick` callback to `PrimaryButton`, and the skin alone determines the visual form

#### Scenario: The leave action is a flat icon component with no appearance params
- **WHEN** a screen renders the leave action
- **THEN** it passes only an accessibility label and an `onClick` callback; the flat, icon-only treatment and the Logout glyph are the skin's choice, with no color, Modifier, or Material 3 type in the signature

#### Scenario: The confirmation dialog is semantic
- **WHEN** a screen raises a confirmation
- **THEN** it passes only title text, an optional body, confirm/cancel labels, and `onConfirm`/`onDismiss` callbacks, and the skin alone determines the dialog's visual form

#### Scenario: The destructive confirmation dialog is semantic and chosen by component, not flag
- **WHEN** a screen raises a confirmation whose confirm action is destructive
- **THEN** it picks `AppDestructiveConfirmDialog` — whose signature is identical to `AppConfirmDialog` — passing no appearance parameter and no destructiveness flag; the destructive treatment is the skin's

#### Scenario: The heading edit affordance is semantic
- **WHEN** a screen supplies `onEditHeading`
- **THEN** it passes only the callback, and the glyph, placement beside the heading, and accessibility label are the skin's choice, with no color, `Modifier`, or Material 3 type in the signature

#### Scenario: A null heading edit callback renders no affordance
- **WHEN** a screen composes `ScreenLayout` without `onEditHeading`
- **THEN** no edit control is rendered beside the heading and the layout is unchanged from its previous behaviour

#### Scenario: The heading affordance is a visible control, unlike the hidden title gesture
- **WHEN** the accessibility tree of a screen supplying both `onEditHeading` and `onTitleDoubleTap` is inspected
- **THEN** the heading's edit affordance exposes a click action and a label, while the app-name label still exposes neither
