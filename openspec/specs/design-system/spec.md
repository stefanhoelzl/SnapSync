# design system Specification

## Purpose

The semantic `App*` component layer that screens compose from, containing all Material 3 styling so a future skin swap is a components-module change only.
## Requirements
### Requirement: Semantic-only components

Design-system (`App*`) components SHALL expose parameters that carry data and meaning only — text, fractions, sealed semantic values, and action callbacks. They MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) and MUST NOT expose `Modifier` parameters. No Material 3 type may appear in any design-system signature. Inventory after this change: `AppTheme`, `ScreenLayout(title, heading?, bottomActions?)` (the optional slot carries a container-arranged cluster of action composables, centered across the width), `StatusHero(indicator, headline, detail?)` with sealed `StatusIndicator` (`Success`, `Error`, `Waiting`, `Photos`, `Loading`, `InProgress`, `Complete`), `PrimaryButton(label, onClick)`, a borderless `SecondaryButton(label, onClick)`, a flat icon-only **leave** action component (label/`onClick` only — the glyph is chosen by the skin, not passed in), a flat icon-only **share** action component (label/`onClick` only — likewise glyph-by-skin), `AppQrCode(content, caption?)` (renders a scannable QR of the `content` string plus an optional caption beneath it — the QR-rendering library is the skin's choice, not a parameter), `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)`, and `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss, body?)` — signature-identical to `AppConfirmDialog`, for confirmations whose confirm action is **destructive** (irreversible). Both dialogs carry an **optional `body`** — a plain string of explanatory text rendered beneath the title in the iOS-alert anatomy — because a bare title cannot always state a consequence (a switch's participation reset, a leave's effect); `body?` carries text only and no appearance. Emphasis and role remain design-time choices expressed as distinct components, never appearance parameters — including whether a confirmation is destructive, which is carried by *which dialog component the call site picks*, not by a flag. The inventory grows demand-driven with the screens that need it (the switch-section, minor-section, checkmark-row, event-hero, cutoff-choice, error-banner, and join-gate components added by this change are specified in their own requirements below).

#### Scenario: Component signatures are appearance-free
- **WHEN** the public signatures of the design-system components are inspected
- **THEN** no parameter accepts a color, text style, shape, Modifier, or any Material 3 type

#### Scenario: The confirmation dialog carries an optional body
- **WHEN** a screen raises a confirmation that needs to state a consequence
- **THEN** it passes a `body` string alongside the title and confirm/cancel labels, and the skin renders it beneath the title with no appearance parameter crossing the signature

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

The design system SHALL provide an `App*` date/time picker — the app's temporal input — that the create
screen's start-date section and the join surface's Custom cutoff choice compose from. Its signature SHALL
expose data-and-meaning parameters only: the current value as a **plain, platform-neutral date-time
value** (not a Material 3 type — e.g. a `kotlinx-datetime` local date-time, or `null` for unset), a change
callback invoked with the newly-picked value, an enabled flag, and an optional **minimum** (the floor the
caller enforces). It MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) or a
`Modifier` parameter, and **no Material 3 type may appear in its signature**.

The picker SHALL be **hand-drawn**, not built from the Material 3 `DatePicker` / `TimePicker` / clock
dial. It SHALL render in-tree as a `Popup` (not a window-centered `AlertDialog`), because the M3
`DatePicker` is a window-centered overlay that clipped on a 390pt phone pane. It SHALL collect the date
and the time in a **single dialog**:

- a **drawn calendar** for the date (days before the supplied minimum rendered unselectable);
- **time wheels** for the hour and minute (a snapping scrollable column per field — chosen over ±1
  steppers, which made a distant time absurd to reach);

and a single confirmation commits both. The time SHALL NOT be typed. The Material 3 `DatePicker` /
`TimePicker` SHALL NOT be used anywhere; the drawn calendar, the wheels, and the `Popup` live inside the
components module (per the Material 3 containment rule). The component SHALL surface the current value in
a readable form.

#### Scenario: The date/time component signature is appearance-free
- **WHEN** the public signature of the date/time picker is inspected
- **THEN** it carries only the current date-time value, a change callback, an enabled flag, and an optional minimum — no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Date and time are picked in one hand-drawn dialog
- **WHEN** the user opens the picker
- **THEN** a single `Popup` shows the drawn calendar and the time wheels together, and one confirmation commits both

#### Scenario: No Material 3 date/time picker is used
- **WHEN** module dependencies and imports are inspected
- **THEN** no Material 3 `DatePicker` / `TimePicker` / clock dial appears anywhere; the drawn calendar, the time wheels, and the `Popup` live only inside the components module, never in any screen module or `App*` signature

#### Scenario: The minimum floors the calendar
- **WHEN** the picker is given a minimum date-time
- **THEN** days before that minimum render unselectable, and the caller additionally coerces a confirmed value up to the floor (a day-grain calendar cannot forbid an earlier hour on the floor's own day)

#### Scenario: Picking a date and time reports the new value
- **WHEN** the user opens the picker and picks a date and a time
- **THEN** it invokes the change callback with the newly-picked date-time value and shows that value

#### Scenario: Disabled input rejects changes
- **WHEN** the picker is rendered with `enabled = false`
- **THEN** it does not open and does not invoke the change callback

### Requirement: App event start-date row component

The design system SHALL provide an `App*` **start-date section** component (superseding the earlier bare
label-plus-affordance row), which the create screen
composes to display and edit an event's start alongside a stated consequence. Its signature SHALL expose
data-and-meaning parameters only: the current start as a **plain, platform-neutral date-time value** (not
a Material 3 type), a change callback invoked with the newly-picked value, and a **note** string stating
the start's consequence. It MUST NOT expose appearance parameters (colors, text styles, shapes,
elevations) or a `Modifier` parameter, and **no Material 3 type may appear in its signature**.

The component SHALL render the current value as a **readable label** with an **edit affordance beside
it**, and activating that affordance SHALL open the design system's date/time picker. Beneath (or beside)
the value it SHALL render the supplied consequence note. The value is **required** — there is no
unset/`null` state, because an event always has a start.

Rendering the label, the edit affordance, and the note as one component (rather than composing them at
the screen) is what keeps the arrangement — and the "the start is a consequence, here is what it means" —
a **convention** owned by the design system rather than a layout each screen re-derives.

#### Scenario: The start section signature is appearance-free
- **WHEN** the public signature of the start-date section is inspected
- **THEN** it carries only the current date-time value, a change callback, and a note string — no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The section shows the value, an edit affordance, and the consequence note
- **WHEN** the start section is rendered with a value and a note
- **THEN** it displays that value in a readable form with an edit affordance and the note stating the start's consequence

#### Scenario: Editing opens the picker and reports the new value
- **WHEN** the user activates the edit affordance and picks a date and time
- **THEN** the component invokes its change callback with the newly-picked value and displays it

### Requirement: App cutoff-preset selector component

The design system SHALL provide the `App*` capture-date cutoff selector as **cutoff choice rows**
(superseding the earlier two-preset segmented control), which the join screen's Share section composes to
choose a capture-date cutoff. The selection SHALL be a **sealed semantic value** with three
members (per the runtime-data-variants rule) — **`Now`** / **`EventStart`** / **`Custom`**. Its signature
SHALL expose data-and-meaning parameters only: the selected choice, a selection callback, a flag for
whether the `Now` choice is **available**, a callback for a Custom pick, and the **floor** (a plain,
platform-neutral date-time value). It MUST NOT expose appearance parameters or a `Modifier`, and **no
Material 3 type may appear in its signature**.

The component SHALL render the three choices as **stacked, embeddable rows** — each with its option name,
a one-line consequence, and a trailing checkmark on the chosen one — **not** as a card of its own, so the
rows embed inside the Share section's card (share and "from when" are one decision surface). Selecting
**Custom** SHALL open the design system's date+time picker **directly**; the component SHALL NOT restate
the chosen instant in the row (the embedding section's own value line is the single statement of the
resulting cutoff). Confirming the picker commits the choice (coerced to the floor); cancelling SHALL leave
the previous selection untouched. When the `Now` choice is **unavailable** it SHALL be rendered **disabled**
rather than hidden, so the control's shape does not change between events.

#### Scenario: The choice rows are appearance-free and the selection is a three-member sealed value
- **WHEN** the public signature of the cutoff choice rows is inspected
- **THEN** the selection is a sealed value with `Now` / `EventStart` / `Custom`, and the signature carries no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The rows embed and do not restate the instant
- **WHEN** the rows are rendered inside the Share section
- **THEN** they show the three options with a trailing checkmark on the selected one, embedded (no card of their own), and never restate the resulting instant the section's value line already carries

#### Scenario: Custom opens the picker directly and only its confirm commits
- **WHEN** the user selects the `Custom` row
- **THEN** the date+time picker opens immediately; confirming it commits the picked (floored) value via the Custom-pick callback and selects `Custom`, and cancelling leaves the previous selection untouched

#### Scenario: An unavailable Now choice is disabled, not hidden
- **WHEN** the rows are rendered with the `Now` choice marked unavailable
- **THEN** the `Now` row renders disabled and does not invoke the selection callback on tap, and the control keeps all three rows

### Requirement: App switch-header section and its sub-levels

The design system SHALL provide an `App*` **switch-header section** — a card whose header is a title plus
an on/off switch, with a content slot for consequence lines — and the two secondary-level building blocks
it composes with: a **recessed sub-section well** (holding a section's second-level rows) and a
**standalone minor section**. It SHALL also provide a **section note** (a muted, one-line consequence) and
a **section value** (the section's single bold statement). Every signature SHALL be appearance-free (a
title, a checked state, a change callback, a content slot — no colors, shapes, text styles, `Modifier`, or
Material 3 type).

The section header's **whole row** SHALL be the single toggle target, carrying `Role.Switch`, so assistive
technology announces exactly one on/off switch per section (two live targets in one row double-fire and
read as a dead control). The switch itself SHALL be **hand-drawn** to iOS metrics, not the Material 3
`Switch` (whose thicker, outlined-thumb track reads as Android and whose default off colours invert in
dark mode); the drawn switch is drawing only, the enclosing row owning the gesture and the semantics. The
two-level grammar — the switch turns the section on, the checkmark rows in the recessed well configure it
— SHALL be uniform, so the screen's idioms are a hierarchy, not a mix. The recess SHALL be achieved by
contrast against the card surface using the frozen palette (no new colour).

#### Scenario: The section header is one Role.Switch tap target with a drawn switch
- **WHEN** a switch-header section renders
- **THEN** the whole header row is one toggle target carrying `Role.Switch`, the switch is hand-drawn (not the Material 3 `Switch`), and assistive technology announces a single on/off switch

#### Scenario: The section building blocks are appearance-free
- **WHEN** the public signatures of the switch section, the sub-section well, the minor section, the section note, and the section value are inspected
- **THEN** each carries only text/state/callback/content parameters — no colors, shapes, text styles, `Modifier`, or Material 3 type

#### Scenario: The sub-section recesses without a new colour
- **WHEN** a section's second-level rows render inside the sub-section well
- **THEN** the well recesses by contrast against the card surface using the frozen palette, in both light and dark, introducing no new colour token

### Requirement: App trailing-checkmark toggle row

The design system SHALL provide an `App*` **trailing-checkmark toggle row** — a row with a label, an
optional note, and a trailing checkmark affordance — for a second-level choice that commits with the
screen's primary action rather than immediately. Its signature SHALL expose data-and-meaning parameters
only (a label, a checked state, a change callback, an optional note, a dimmed flag, a divider flag): no
colors, shapes, text styles, `Modifier`, or Material 3 type.

The whole row SHALL be one toggle target carrying **`Role.Checkbox`** — a checkbox, not a switch, because
the choice does not apply immediately (a switch's "applies now" contract would be untrue). When **dimmed**
the row SHALL remain in the accessibility tree as a **disabled** checkbox (`enabled = false`) rather than
dropping its semantics, so assistive technology still finds a control and reports it unavailable. The
unchecked state SHALL draw an empty affordance.

#### Scenario: The toggle row is a single Role.Checkbox target
- **WHEN** a trailing-checkmark toggle row renders
- **THEN** the whole row is one toggle target carrying `Role.Checkbox`, with an empty affordance when unchecked and a checkmark when checked

#### Scenario: A dimmed row stays a present-but-disabled checkbox
- **WHEN** the row is rendered dimmed
- **THEN** it remains a disabled checkbox in the semantics tree, reported unavailable rather than absent

#### Scenario: The row signature is appearance-free
- **WHEN** the public signature of the toggle row is inspected
- **THEN** it carries only the label, checked state, change callback, optional note, dimmed flag, and divider flag — no colors, shapes, text styles, `Modifier`, or Material 3 type

### Requirement: App mark, event hero, and eyebrow

The design system SHALL provide the app's **drawn mark badge** (the product mark rendered from `Canvas`
paths matching the app-icon geometry, sized by parameter), the **event-hero header** variants that pin
the mark and an eyebrow across a surface's phases (a loading placeholder, a compact left-aligned form, and
a host-framed form), and an **eyebrow** label (an uppercase, tracked line with an `Accent` / `Muted`
tone). Every signature SHALL be appearance-free (text and a tone/size value only — no colors, shapes, text
styles, `Modifier`, or Material 3 type). Pinning the mark and eyebrow in one header component is what keeps
a surface's identity from jumping between phases (Loading → Explain → Ready → Committing; create form →
creating), a **convention** the design system owns rather than a layout each screen re-derives.

#### Scenario: The mark is drawn and sized by parameter
- **WHEN** the app mark badge renders
- **THEN** it is drawn from `Canvas` paths (the app-icon geometry) at the requested size, with no glyph asset or Material 3 type in its signature

#### Scenario: The event hero pins identity across phases
- **WHEN** a surface renders the event-hero header across its phases
- **THEN** the mark and eyebrow hold their place while only the phase body changes, and the header signature carries no colors, shapes, text styles, `Modifier`, or Material 3 type

#### Scenario: The eyebrow is a semantic toned label
- **WHEN** an eyebrow renders
- **THEN** it takes only the text and an `Accent` / `Muted` tone, the uppercase/tracking/colour being the skin's

### Requirement: App error banner

The design system SHALL provide an `App*` **error banner** for a submission-level failure surfaced after
an action the user just took — a bordered surface holding the message, announced politely (not as an
alert). Its signature SHALL carry **text only**: no `Modifier`, color, shape, text style, or Material 3
type. It is deliberately not a reddened field: a submission failure is not a live field-validation error,
so it is stated above the action rather than on the input.

#### Scenario: The error banner is text-only and appearance-free
- **WHEN** the public signature of the error banner is inspected
- **THEN** it carries only the message text — no `Modifier`, color, shape, text style, or Material 3 type

#### Scenario: The banner announces a submission failure politely
- **WHEN** a submission fails and the banner renders
- **THEN** it shows the message in a bordered surface above the action, announced politely rather than as an alert, and never reddens an input field

### Requirement: App join-gate surface pieces

The design system SHALL provide the join-gate surface pieces the redesigned gate composes: a **neutral
notice card** (an icon, a title, and a body — for the gate's error phases), a **consent-fact row** (an
icon, a title, and a body — a single "what joining does" point), a **loading invitation header**, and a
**calm progress block** (a message beside a spinner). Every signature SHALL be appearance-free (content
plus sealed/semantic values only — no colors, shapes, text styles, `Modifier`, or Material 3 type), with
the icon glyphs contained in the components module. The notice card SHALL **not** be styled as a fault:
the gate's errors (invalid invite, load failed, join failed) are neutral notices, never red — a missing or
unreachable event is not the user's mistake.

#### Scenario: The join-gate pieces are appearance-free with contained glyphs
- **WHEN** the public signatures of the notice card, consent-fact row, loading header, and progress block are inspected
- **THEN** each carries only content and sealed/semantic values — no colors, shapes, text styles, `Modifier`, or Material 3 type — and the icon glyphs live inside the components module

#### Scenario: Gate errors are neutral notices, not faults
- **WHEN** the gate renders an invalid-invite, load-failed, or join-failed notice
- **THEN** it is a neutral notice card (icon + title + body), never a red/error treatment

### Requirement: Interactive rows carry roles and states, and copy re-derives from state

Every interactive row the design system renders SHALL carry an explicit accessibility **role and state**
(the section header a `Role.Switch`, the toggle row a `Role.Checkbox`), SHALL be a **single tap target**
per row (so assistive technology announces exactly one control), and SHALL keep a **dimmed** control
present-but-disabled in the semantics tree rather than dropping it. The design system SHALL honour the
platform **reduce-motion** preference (a pulsing status arrow renders without motion under it). Any
**consequence line** a component renders SHALL be derivable from the state passed to it — a component
SHALL NOT hard-code a consequence that could contradict the state (the album note names the produced
feeds, the cutoff value states the committed instant), so the surface can never assert a feed or an
instant the membership will not produce.

#### Scenario: Rows announce one control with an explicit role and state
- **WHEN** assistive technology inspects a switch-header row or a trailing-checkmark row
- **THEN** it finds exactly one control per row carrying the correct role (`Switch` / `Checkbox`) and its checked/enabled state

#### Scenario: Reduce-motion is honoured
- **WHEN** the platform reduce-motion preference is set and a pulsing status arrow renders
- **THEN** the arrow renders without motion

#### Scenario: A consequence line re-derives from state
- **WHEN** a component renders a consequence line (an album note, a cutoff value)
- **THEN** the line is computed from the state passed to it, never a hard-coded string that could contradict the current selection

### Requirement: Measured contrast in both themes over a frozen palette

The Material 3 skin SHALL meet a measured **AA** contrast baseline in **both** light and dark themes, and
SHALL express the redesign's surfaces (switch sections, recessed wells, drawn switch on/off states) over a
**frozen palette** — no new colour token per component. Control colours that the stock M3 pairing gets
wrong SHALL be pinned: the drawn switch's **off** colours SHALL keep the thumb the lighter element in both
themes (the stock `surface`-on-`outlineVariant` pairing inverts the thumb darker than the track in dark
mode), and recessed wells SHALL recess by contrast against the card surface rather than by a new colour.
These are skin-local choices and SHALL NOT introduce any appearance parameter on an `App*` signature.

#### Scenario: The off switch keeps the thumb lighter in both themes
- **WHEN** the drawn switch renders off in light and in dark
- **THEN** the thumb is the lighter element in both, not a hole in dark mode, using the frozen palette

#### Scenario: Contrast corrections add no appearance parameter
- **WHEN** the light-mode contrast corrections and the pinned control colours are applied
- **THEN** they live in the skin and no color, shape, or text-style parameter appears on any `App*` signature

