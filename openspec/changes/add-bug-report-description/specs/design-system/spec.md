## MODIFIED Requirements

### Requirement: AppTextField semantic component

The design system SHALL provide an `AppTextField` semantic component — the app's first text input —
that the create-event screen composes from. `AppTextField(value, onValueChange, placeholder, enabled,
maxLength, singleLine)` SHALL expose data-and-meaning parameters only: the current string value, a
change callback, a placeholder string, an enabled flag, a maximum character count, and whether the
input is a single line or accepts multiple. It MUST NOT expose appearance parameters (colors, text
styles, shapes, elevations) or a `Modifier` parameter, and no Material 3 type may appear in its
signature; the Material 3 text-field containment lives inside the component. The component SHALL
enforce `maxLength` by refusing input beyond it.

Single-line SHALL remain the default, so every existing call site keeps its behaviour unchanged. A
multi-line field SHALL wrap its text and show more than one line at rest, so a written account is
readable while it is being composed rather than scrolling out of view horizontally. Line count is a
property of the same input, not grounds for a separate component.

#### Scenario: AppTextField signature is appearance-free
- **WHEN** the public signature of `AppTextField` is inspected
- **THEN** it carries only the value, change callback, placeholder, enabled flag, max length, and
  line-mode flag — no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Max length is enforced by the component
- **WHEN** the field already holds `maxLength` characters and more input arrives
- **THEN** the value does not grow beyond `maxLength`

#### Scenario: Disabled field rejects input
- **WHEN** `AppTextField` is rendered with `enabled = false`
- **THEN** it does not invoke `onValueChange`

#### Scenario: Single line is the default
- **WHEN** `AppTextField` is composed without specifying a line mode
- **THEN** it renders as a single-line input, unchanged from its previous behaviour

#### Scenario: A multi-line field wraps
- **WHEN** `AppTextField` is composed as multi-line and given text longer than one line
- **THEN** the text wraps onto further lines rather than scrolling horizontally

## ADDED Requirements

### Requirement: App bug-report sheet component

The design system SHALL provide an `AppBugReportSheet` semantic component — the app's first bottom
sheet — which collects a written description and offers a confirm and a cancel action. Its signature
SHALL expose data-and-meaning parameters only: a title, a body line naming what will be sent, a
placeholder for the input, a maximum character count, the two action labels, a confirm callback
receiving the entered description, and a dismiss callback. It MUST NOT expose appearance parameters
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

The confirm action SHALL be disabled while the entered description, once trimmed, is empty, and the
value passed to the confirm callback SHALL be trimmed. Dismissing by the cancel action, the scrim, or
a dismissal gesture SHALL route to the dismiss callback, identically.

#### Scenario: Sheet signature is appearance-free
- **WHEN** the public signature of `AppBugReportSheet` is inspected
- **THEN** it carries only the title, body, placeholder, max length, action labels, and the two
  callbacks — no colors, text styles, shapes, elevations, `Modifier`, content slot, or Material 3 type

#### Scenario: Confirm is inert until something is written
- **WHEN** the sheet is shown and the input is empty or holds only whitespace
- **THEN** the confirm action is disabled and cannot invoke the confirm callback

#### Scenario: The confirmed value is trimmed
- **WHEN** the operator writes a description surrounded by whitespace and confirms
- **THEN** the confirm callback receives the trimmed description

#### Scenario: Every dismissal route is one route
- **WHEN** the sheet is dismissed by the cancel action, by the scrim, or by a dismissal gesture
- **THEN** the dismiss callback runs and the confirm callback does not
