# design system Specification

## Purpose

The semantic `App*` component layer that screens compose from, containing all Material 3 styling so a future skin swap is a components-module change only.
## Requirements
### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, heading?, bottomActions?)` (the optional slot carries a container-arranged cluster of action composables, centered across the width), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Error`, `Waiting`, `Photos`, `Loading`, `InProgress`, `Complete`), `PrimaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it.

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: Progress is expressed as meaning, not styling
- **WHEN** a screen displays an in-progress pass
- **THEN** it passes only `StatusIndicator.InProgress` to `StatusHero`, and the skin alone determines the visual form — the screen never says how far along, because the status surface reports a mood, not a number (capability `sync-status-screen`)

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

### Requirement: Material 3 containment

Within the product UI, only the design-system components module SHALL depend on or import Material 3 — including the Material icon artifact (`compose.materialIconsExtended`), which is used solely inside the components module to render glyphs (e.g. the leave action's `Icons.AutoMirrored.Filled.Logout` and the share action's glyph) — and likewise the QR-rendering library used by `AppQrCode`, whose import SHALL be confined to the components module; the `Icons.*` import and the QR library import SHALL NOT appear in any screen module or in any `App*` signature. Screens are composed exclusively of `App*` components plus meaning-free layout primitives (e.g. `Column`, `Spacer`), so a future skin (e.g. Cupertino) — or a swap of the QR-rendering library — is a components-module change only. The desktop harness's control panel is exempt: it is test equipment and deliberately uses raw Material 3, never `App*` components (asymmetric investment).

#### Scenario: Material 3 is contained
- **WHEN** module dependencies and imports are inspected
- **THEN** Material 3 — and the Material icon artifact — appears only in the design-system components module and the desktop harness's control-panel code, never in screen modules

#### Scenario: Icon glyphs do not leak into screens
- **WHEN** the leave or share action's glyph is rendered
- **THEN** the `Icons.*` reference lives in the components module's skin, and the screen passes only the semantic action component

#### Scenario: The QR-rendering library does not leak into screens
- **WHEN** the QR is rendered
- **THEN** the QR-rendering library import lives only in the components module's `AppQrCode`, and the screen passes only the `content` string and caption text

### Requirement: Semantic containers own convention-bearing arrangement

Where platform conventions hold opinions about arrangement (screen insets, title placement, the status screen's centered hero, bottom-anchored screen actions, action ordering/stacking — later: grouped lists), screens SHALL express the arrangement through semantic slotted containers rather than raw geometry, so a skin can re-arrange without touching screens. `ScreenLayout(title, heading?, bottomActions?) { content }` owns the screen's edge insets, title placement, an optional heading, the vertical centering of the body content, and the placement and arrangement of an optional bottom **action cluster** — the screen supplies one or more action composables and the container owns where they sit and how they are spaced and ordered, centered across the width. `StatusHero` owns the hero's internal arrangement (indicator inline-left of the headline, muted detail line beneath) and its typographic hierarchy. `AppQrCode` owns the QR's internal arrangement (the QR above its optional caption, spacing, caption emphasis). Raw layout primitives remain permitted only for meaning-free geometry no platform convention covers.

#### Scenario: Screen structure goes through the container
- **WHEN** the status screen is composed
- **THEN** its title, edge insets, and body centering come from `ScreenLayout`, and the screen body contains no hardcoded screen-level inset, title placement, or centering

#### Scenario: Bottom action placement goes through the container
- **WHEN** the status screen renders its leave and share actions in the joined layer
- **THEN** the actions' placement, spacing, and centered arrangement come from `ScreenLayout`'s cluster slot, and the screen hardcodes no bottom-anchor or row geometry

#### Scenario: Hero arrangement goes through the component
- **WHEN** a screen renders a status hero
- **THEN** the icon/headline/detail arrangement, spacing, and the muted detail emphasis come from `StatusHero`, not from the screen

#### Scenario: QR arrangement goes through the component
- **WHEN** a screen renders the invite QR with a caption
- **THEN** the QR-above-caption arrangement, spacing, and caption emphasis come from `AppQrCode`, not from the screen

### Requirement: Runtime-data variants use sealed semantic values

Variant axes that are design-time choices (a call site statically picks one, e.g. button emphasis) SHALL be distinct components (`PrimaryButton`, not `AppButton(role = ...)`). Variant axes driven by runtime data (the variant arrives from state, possibly carrying a payload) SHALL be sealed semantic value parameters (e.g. `StatusIndicator`, whose `Progress` variant carries a fraction). Enum- or value-shaped parameters whose meaning is appearance remain banned in both cases.

#### Scenario: Data-driven indicator is a sealed value
- **WHEN** the status screen branches on UI state to render the hero
- **THEN** it selects a `StatusIndicator` value (not a different component per state), and only the `Progress` variant carries data

### Requirement: AppTextField semantic component

The design system SHALL provide an `AppTextField` semantic component — the app's first text input —
that the create-event screen composes from. `AppTextField(value, onValueChange, placeholder, enabled,
maxLength)` SHALL expose data-and-meaning parameters only: the current string value, a change
callback, a placeholder string, an enabled flag, and a maximum character count. It MUST NOT expose
appearance parameters (colors, text styles, shapes, elevations) or a `Modifier` parameter, and no
Material 3 type may appear in its signature; the Material 3 text-field containment lives inside the
component. The component SHALL enforce `maxLength` by refusing input beyond it.

#### Scenario: AppTextField signature is appearance-free
- **WHEN** the public signature of `AppTextField` is inspected
- **THEN** it carries only the value, change callback, placeholder, enabled flag, and max length — no
  colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Max length is enforced by the component
- **WHEN** the field already holds `maxLength` characters and more input arrives
- **THEN** the value does not grow beyond `maxLength`

#### Scenario: Disabled field rejects input
- **WHEN** `AppTextField` is rendered with `enabled = false`
- **THEN** it does not invoke `onValueChange`

### Requirement: App status-line component

The design system SHALL provide a semantic status-line component that renders the joined-layer sync
health from a single sealed semantic value (e.g. `InSync` / `Syncing(uploadArrow, downloadArrow)` /
`NeedsAccess` / **`NotStarted(startsAt)`** / **`CannotVerifyDevice`**), where each arrow state is one of
`Hidden` / `Static` / `Pulsing` — carried, since migration step 9's Arrow/ArrowLevel unification, by
the **one shared `model/` `Arrow` enum** (`:ui:components` takes an api dependency on `:domain` for
it): the arrow is shared sync vocabulary, not a config-capability coupling, so presentation's
reduction and this skin render from the same declaration and a mapping layer cannot drift. Per the semantic-only rule it SHALL expose **no** appearance parameters (no `Modifier`, color,
shape, or text style) — callers pass only the health value and, for the attention state, an `onClick`. The
component SHALL animate a `Pulsing` arrow and render a `Static` arrow without motion, SHALL render the
two **attention** states (`NeedsAccess` and `CannotVerifyDevice`) as the **only** variants carrying a
background, and SHALL respect reduced-motion preferences. It SHALL surface **no numeric counts**.

The two attention states are not peers, and the component SHALL distinguish them: `NeedsAccess` is
**tappable** and carries a chevron, because the member can fix it; `CannotVerifyDevice` is **not** tappable
and carries **no** chevron, because they cannot (capability `sync-status-screen`). Background means "look at
this"; a chevron means "do something about it", and only one of them earns the second.

For the **`CannotVerifyDevice`** value the component SHALL render an attention indicator and a label
stating that this device cannot be verified. It takes **no** `onClick` — the absence of the parameter is
what makes the un-tappability structural rather than a call-site convention.

For the **`NotStarted`** value the component SHALL render a **clock** indicator and a label naming the
event's start — of the form **"Starts &lt;date&gt;, &lt;time&gt;"** — formatted in the device's **local**
timezone. The value carried is a plain, platform-neutral date-time (no Material 3 type). It SHALL be flat
(no background) and **not** tappable: it is information, not an action. The label string and its date
formatting are owned by the component (as "In sync" already is).

For the `Syncing` value the component SHALL choose the label from the arrows' activity: when **any**
shown arrow is `Pulsing` the label SHALL read **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is `Pulsing` the label SHALL read **"Synchronization pending…"**. These label strings
are owned by the component (as "In sync" already is). The Material 3 skin SHALL tint a `Static` arrow
with a muted/neutral color (gray) and a `Pulsing` arrow with the brand **primary** accent; these color
mappings are skin-local and SHALL NOT appear on any `App*` signature.

#### Scenario: The not-started value renders a clock and the start instant
- **WHEN** the status line is given `NotStarted` carrying a start of 14 Jul 2026, 18:00 local
- **THEN** it shows a clock indicator and a label naming that start, flat (no background) and not
  tappable, with no arrows and no counts

#### Scenario: Pulsing arrow drives the ongoing label
- **WHEN** the status line is given `Syncing(upload = Pulsing, download = Hidden)`
- **THEN** it shows the upload arrow animating in the brand primary, no download arrow, and the
  "Synchronization ongoing…" label, with no counts and no exposed appearance parameters

#### Scenario: Static-only arrows drive the pending label
- **WHEN** the status line is given `Syncing(upload = Static, download = Hidden)`
- **THEN** it shows the upload arrow static in a muted gray (no motion), no download arrow, and the
  "Synchronization pending…" label

#### Scenario: Only the attention states have a background
- **WHEN** the status line renders `InSync`, `Syncing`, or `NotStarted`
- **THEN** it is flat (no background)

#### Scenario: The two attention states differ by what they ask of the user
- **WHEN** the status line renders `NeedsAccess`
- **THEN** it carries a background and a chevron, and invokes `onClick` on tap
- **WHEN** it renders `CannotVerifyDevice`
- **THEN** it carries a background and **no** chevron, and there is no tap to invoke — the member has nothing to do

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

### Requirement: Status accents unified on the brand primary

The Material 3 skin SHALL render the live/complete status accents with the brand **primary** color
rather than a distinct green: the status-line `Pulsing` arrow and the LED-style `StatusIndicator`
active/complete dots (e.g. `Complete`) SHALL use primary. No standalone green accent SHALL remain for
these status indicators. These are skin-local color choices and SHALL NOT introduce any appearance
parameter on an `App*` signature.

#### Scenario: The complete indicator uses primary, not green
- **WHEN** the skin renders the `Complete` `StatusIndicator` dot or a `Pulsing` status-line arrow
- **THEN** it is tinted with the brand primary color, and no green accent is used

### Requirement: App date/time input component

The design system SHALL provide an `App*` date/time input component — the app's temporal input — that
the create screen's start-date row composes from. Its signature SHALL expose data-and-meaning parameters
only: the current value as a **plain, platform-neutral date-time value** (not a Material 3 type — e.g. a
simple domain/`kotlinx-datetime` local date-time, or `null` for unset), a change callback invoked with
the newly-picked value, and an enabled flag. It MUST NOT expose appearance parameters (colors, text
styles, shapes, elevations) or a `Modifier` parameter, and **no Material 3 type may appear in its
signature**. The Material 3 `DatePicker` and `TimePicker` (and any dialog scaffolding) SHALL be
**contained inside** the component, per the Material 3 containment rule.

The component SHALL collect the date and the time in a **single dialog**, replacing the prior two-step
(date → Next → time → OK) flow:

- the dialog SHALL show a calendar for the date and, beneath it, the chosen time as **hour and minute
  fields**;
- activating an hour or minute field SHALL swap the calendar area for the Material 3 **clock dial**,
  with the hour/minute readout remaining visible, and a return to the calendar;
- the time SHALL NOT be typed — the dial is the editor, and the hour/minute fields are its readout and
  its tap target.

The component SHALL surface the current value in a readable form and SHALL NOT itself impose date bounds
(bounds, defaults, and any "shortcut" action are the caller's concern).

#### Scenario: The date/time component signature is appearance-free
- **WHEN** the public signature of the date/time component is inspected
- **THEN** it carries only the current date-time value, a change callback, and an enabled flag — no
  colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Date and time are picked in one dialog
- **WHEN** the user opens the component
- **THEN** a single dialog shows the calendar and the hour/minute fields together, and one confirmation
  commits both

#### Scenario: Tapping the time opens the dial
- **WHEN** the user activates the hour or minute field
- **THEN** the calendar area is replaced by the clock dial, the hour/minute readout stays visible, and the
  user can return to the calendar without losing the picked date

#### Scenario: Picking a date and time reports the new value
- **WHEN** the user opens the component and picks a date and a time
- **THEN** it invokes the change callback with the newly-picked date-time value and shows that value

#### Scenario: Material 3 pickers are contained in the component
- **WHEN** module dependencies and imports are inspected
- **THEN** the Material 3 `DatePicker` / `TimePicker` imports appear only inside the components module,
  never in any screen module or `App*` signature

#### Scenario: Disabled input rejects changes
- **WHEN** the component is rendered with `enabled = false`
- **THEN** it does not open a picker and does not invoke the change callback

### Requirement: App explainer component

The design system SHALL provide an `AppExplainer` semantic component for a full-screen explanation
that precedes a consequential system prompt: a neutral indicator, a headline, and a body of one or
more short paragraphs.

Its signature SHALL carry **content only** — a headline and an ordered list of paragraphs — and no
appearance parameters: no `Modifier`, no color, no shape, no text style, and no Material 3 type
(consistent with "Semantic-only components").

The component SHALL render the **neutral** photo-library indicator (`StatusIndicator.Photos`) — an ask,
not a fault — and SHALL NOT use a warning or error treatment. Paragraph spacing SHALL be owned by the
component, not by the calling screen, so no caller composes raw geometry (consistent with "Semantic
containers own convention-bearing arrangement").

The component SHALL NOT own its actions. The calling screen SHALL pin its confirm and cancel actions in
the same bottom action cluster every other surface on that screen uses, so the buttons align with the
screen's other phases.

#### Scenario: The explainer renders a neutral ask
- **WHEN** `AppExplainer` renders
- **THEN** the skin draws the neutral photo-library glyph, not a warning or error treatment

#### Scenario: The explainer carries no appearance parameters
- **WHEN** a screen composes `AppExplainer`
- **THEN** it passes only a headline and paragraphs, and the signature exposes no `Modifier`, color, shape, text style, or Material 3 type

#### Scenario: The explainer owns its paragraph arrangement
- **WHEN** a screen supplies more than one paragraph
- **THEN** the component spaces them, and the screen composes no raw geometry to do so

### Requirement: App event start-date row component

The design system SHALL provide an `App*` **start-date row** component, which the create screen composes
to display and edit an event's start. Its signature SHALL expose data-and-meaning parameters only: the
current start as a **plain, platform-neutral date-time value** (not a Material 3 type), and a change
callback invoked with the newly-picked value. It MUST NOT expose appearance parameters (colors, text
styles, shapes, elevations) or a `Modifier` parameter, and **no Material 3 type may appear in its
signature**.

The component SHALL render the current value as a **readable label** with an **edit affordance beside
it**, and activating that affordance SHALL open the design system's date/time picker. The value is
**required** — there is no unset/`null` state, because an event always has a start.

Rendering the label and the edit affordance as one component (rather than composing them at the screen)
is what keeps the arrangement — label left, affordance right, one tap target for editing — a
**convention** owned by the design system rather than a layout each screen re-derives.

#### Scenario: The start row signature is appearance-free
- **WHEN** the public signature of the start-date row is inspected
- **THEN** it carries only the current date-time value and a change callback — no colors, text styles,
  shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The row shows the value with an edit affordance
- **WHEN** the start row is rendered with a value
- **THEN** it displays that value in a readable form with an edit affordance beside it

#### Scenario: Editing opens the picker and reports the new value
- **WHEN** the user activates the edit affordance and picks a date and time
- **THEN** the component invokes its change callback with the newly-picked value and displays it

### Requirement: App cutoff-preset selector component

The design system SHALL provide an `App*` **cutoff-preset selector**, which the join screen composes to
choose a capture-date cutoff. Its signature SHALL expose data-and-meaning parameters only: the selected
preset as a **sealed semantic value** (per the runtime-data-variants rule — e.g. `Now` / `EventStart`), a
selection callback, a flag for whether the `Now` preset is **available**, an `enabled` flag, and the
**resulting instant** to display as a label (a plain, platform-neutral date-time value). It MUST NOT
expose appearance parameters or a `Modifier`, and **no Material 3 type may appear in its signature**.

The component SHALL render the two presets as a segmented control together with the resulting instant as
a label beneath, so the member always sees the value they are committing to. When the `Now` preset is
unavailable it SHALL be rendered **disabled** rather than hidden, so the control's shape does not change
between events.

#### Scenario: The selector signature is appearance-free and sealed
- **WHEN** the public signature of the cutoff-preset selector is inspected
- **THEN** the selection is a sealed semantic value, and the signature carries no colors, text styles,
  shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The selector shows the resulting instant
- **WHEN** the selector is rendered with a selected preset and a resulting instant
- **THEN** it shows both presets as a segmented control and the resulting instant as a readable label

#### Scenario: An unavailable Now preset is disabled, not hidden
- **WHEN** the selector is rendered with the `Now` preset marked unavailable
- **THEN** the `Now` segment renders disabled and does not invoke the selection callback on tap, and the
  control keeps both segments

#### Scenario: Disabled selector rejects changes
- **WHEN** the selector is rendered with `enabled = false`
- **THEN** neither preset invokes the selection callback

