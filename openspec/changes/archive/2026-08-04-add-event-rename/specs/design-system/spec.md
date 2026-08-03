## RENAMED Requirements

- FROM: `### Requirement: App bug-report sheet component`
- TO: `### Requirement: App text-prompt sheet component`

## MODIFIED Requirements

### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, heading?, bottomActions?, onTitleDoubleTap?, onEditHeading?)` (the optional actions slot carries a container-arranged cluster of action composables, centered across the width; `onEditHeading?` is an action callback like any other — an **edit affordance on the heading**, capability `event-rename`, which the container renders beside the heading text as a control with click semantics and an accessibility label chosen by the skin, `null` rendering no affordance at all; it is the deliberate opposite of `onTitleDoubleTap?`, which is hidden, and the two occupy different slots — the heading and the app-name label — so neither gesture can shadow the other; `onTitleDoubleTap?` is an action callback like any other — a **hidden** operator affordance on the app-name label, capability `diagnostic-logging`, which the container wires as a raw pointer gesture exposing no click semantics and no ripple, since an affordance that reads as a control is not hidden; `null` wires no gesture at all), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Error`, `Waiting`, `Photos`, `Loading`, `InProgress`, `Complete`), `PrimaryButton(label, onClick)`, a borderless `SecondaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Both dialogs carry an **optional `body`** — a plain string of explanatory text rendered beneath the title in the iOS-alert anatomy — because a bare title cannot always state a consequence (a switch's participation reset, a leave's effect); `body?` carries text only and no appearance. Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it (the switch-section, minor-section, checkmark-row, event-hero, cutoff-choice, error-banner, and join-gate components added by this change are specified in their own requirements below).

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

### Requirement: App text-prompt sheet component

The design system SHALL provide an `AppTextPromptSheet` semantic component — the app's bottom sheet
for collecting one piece of text — which collects a written value and offers a confirm and a cancel
action. Its signature SHALL expose data-and-meaning parameters only: a title, an optional body line
naming what the value is for, a placeholder for the input, an **initial value** the input opens
carrying, a maximum character count, the two action labels, an **optional error message**, a **busy**
flag, a confirm callback receiving the entered value, and a dismiss callback.

The component is **general, not purpose-named**: it serves the diagnostic dump's bug report (capability
`diagnostic-logging`) and the event rename (capability `event-rename`) as one component, because the
inventory grows demand-driven with the screens that need it and a second near-identical text-prompt
overlay would be two components for one meaning.

The **initial value** SHALL be the input's starting content, so a caller editing an existing value opens
pre-filled; an empty initial value SHALL behave exactly as the input's previous always-empty behaviour.
When an **error message** is present the component SHALL render it as an error banner (capability
`design-system`) above the actions, never as a styling of the input field, so a rejection from a remote
system cannot read as a complaint about what the person typed. While **busy** the component SHALL
remain open, indicate that the action is running, and refuse both the confirm and every dismissal route,
so an in-flight request can neither be double-submitted nor abandoned mid-flight. It MUST NOT expose appearance parameters
(colors, text styles, shapes, elevations), a `Modifier`, or a content slot, and no Material 3 type may
appear in its signature; the Material 3 bottom-sheet containment, the input, and the action
arrangement live inside the component.

The component SHALL own the keyboard-avoidance behaviour: while the software keyboard is shown, the
input and BOTH actions SHALL remain visible, and content that does not fit SHALL scroll within the
sheet. Call sites SHALL NOT be given insets to manage — a sheet whose confirm action can be covered by
the keyboard is unusable, and that is the component's problem to solve once rather than every caller's.

The sheet SHALL achieve this by presenting at **full height**, laying its content out from the top,
and SHALL NOT rely on an IME inset to lift a wrap-height sheet. This is a measured necessity, not a
presentation preference: on an SE2 (iOS 26.5, Compose Multiplatform 1.11.1) a wrap-height sheet with
`imePadding()` applied rendered the confirm action **entirely behind the keyboard** — the IME inset
does not reach the sheet's own popup window, so the padding resolved to zero. Expiry trigger: a
Compose Multiplatform release that propagates IME insets into popup windows on iOS.

#### Scenario: The keyboard never covers the actions
- **WHEN** the sheet is open and the software keyboard is shown
- **THEN** the input and both the confirm and cancel actions are visible and reachable

The confirm action SHALL be disabled while the entered value, once trimmed, is empty, **or while it
equals the trimmed initial value** — so a call site editing an existing value cannot submit a no-op —
and the value passed to the confirm callback SHALL be trimmed. Dismissing by the cancel action, the scrim, or
a dismissal gesture SHALL route to the dismiss callback, identically.

#### Scenario: Sheet signature is appearance-free
- **WHEN** the public signature of `AppTextPromptSheet` is inspected
- **THEN** it carries only the title, body, placeholder, initial value, max length, action labels, error
  message, busy flag, and the two callbacks — no colors, text styles, shapes, elevations, `Modifier`,
  content slot, or Material 3 type

#### Scenario: Confirm is inert until something is written
- **WHEN** the sheet is shown and the input is empty or holds only whitespace
- **THEN** the confirm action is disabled and cannot invoke the confirm callback

#### Scenario: The sheet opens carrying its initial value
- **WHEN** the sheet is shown with an initial value
- **THEN** the input opens holding that value, ready to be edited

#### Scenario: Confirm is inert while the value is unchanged
- **WHEN** the sheet is shown with an initial value and the trimmed input still equals it
- **THEN** the confirm action is disabled, so a no-op submission cannot be made

#### Scenario: An error renders as a banner, never on the field
- **WHEN** the sheet is given an error message
- **THEN** it renders above the actions as an error banner and the input field carries no error styling

#### Scenario: A busy sheet cannot be submitted or dismissed
- **WHEN** the sheet is busy
- **THEN** it stays open indicating the action is running, the confirm callback cannot fire again, and the cancel action, the scrim, and a dismissal gesture all route nowhere

#### Scenario: The confirmed value is trimmed
- **WHEN** the person writes a value surrounded by whitespace and confirms
- **THEN** the confirm callback receives the trimmed value

#### Scenario: Every dismissal route is one route
- **WHEN** the sheet is not busy and is dismissed by the cancel action, by the scrim, or by a dismissal gesture
- **THEN** the dismiss callback runs and the confirm callback does not
