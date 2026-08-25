# design system Specification

## Purpose

The semantic `App*` component layer that screens compose from, containing all Material 3 styling so a future skin swap is a components-module change only.
## Requirements
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

When **both** arrows are `Pulsing` they SHALL animate **in lockstep** — identical opacity at every
instant — **regardless of when each arrow began pulsing**. The two arrows rarely begin together: uploads
start at join while the download arm's total is populated only by the later reconcile, so without this
guarantee the arrows settle into opposite halves of the fade and visibly beat against each other
(reported from a device as *"arrows are not pulsing in sync"*, and measured there: with the two arrows
entering apart, the arrows' opacity difference reached **98% of the full pulse swing** — near
anti-phase — against **0.09%** once they share one phase). An arrow that begins pulsing while the other
already is SHALL adopt the in-progress opacity immediately, rather than starting its own fade — being
briefly out of phase is the very defect this forbids. The animation remains internal: this guarantee SHALL NOT introduce any opacity, animation,
or appearance parameter on the component's signature.

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

#### Scenario: Two pulsing arrows beat together however far apart they started
- **WHEN** the status line is given `Syncing(upload = Pulsing, download = Hidden)` and, part-way through
  the upload arrow's fade, the value changes to `Syncing(upload = Pulsing, download = Pulsing)`
- **THEN** at every instant from then on both arrows render the **same** opacity — the download arrow
  adopts the fade already in progress rather than starting its own

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

The design system SHALL provide flat icon-only action components for the joined-layer **settings**,
**share**, and **leave** actions — no container background in the resting state, only the semantic glyph.
They SHALL follow the semantic-only rule (a description/label and an `onClick`, no appearance parameters),
keeping the underlying icon glyphs contained in the components module. Because leaving an event is
destructive, the **leave** action's glyph SHALL be rendered in the **error** accent (the skin's
`colorScheme.error` role) while the **settings** and **share** actions keep the default content tint; this
is a skin-local color choice that SHALL NOT introduce any appearance parameter on the component's
signature.

#### Scenario: Icon actions are flat
- **WHEN** the settings, share, or leave icon action renders in its resting state
- **THEN** it shows only its glyph with no container background, and exposes no appearance parameters

#### Scenario: The leave icon is tinted with the error accent
- **WHEN** the leave icon action renders
- **THEN** its glyph is tinted with the error accent (marking the destructive action), it remains flat with no container background, and its signature carries no color, Modifier, or Material 3 type

#### Scenario: The settings icon keeps the default content tint
- **WHEN** the settings icon action renders
- **THEN** its glyph uses the default content tint (like share, not the error accent), it remains flat with no container background, and its signature carries only a description/label and an `onClick`

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
screen's start-date section and the join surface's Custom cutoff choice compose from. It SHALL offer two
modes: a **single** date-time value, and a **dual-handle range** of two date-time values (a `[from, until]`
span). In both modes its signature SHALL expose data-and-meaning parameters only: the current value(s) as
**plain, platform-neutral date-time values** (not a Material 3 type — e.g. `kotlinx-datetime` local
date-times, or `null` for unset in single mode), a change callback invoked with the newly-picked value(s),
an enabled flag, and an optional selectable **window** — a `[min, max]` bound the caller supplies (either
end may be absent). The single mode's optional **minimum** is the `min` end of that window with no `max`.
It MUST NOT expose appearance parameters (colors, text styles, shapes, elevations) or a `Modifier`
parameter, and **no Material 3 type may appear in its signature**.

The picker SHALL be **hand-drawn**, not built from the Material 3 `DatePicker` / `TimePicker` / clock
dial. It SHALL render in-tree as a `Popup` (not a window-centered `AlertDialog`), because the M3
`DatePicker` is a window-centered overlay that clipped on a 390pt phone pane. It SHALL collect the date(s)
and the time(s) in a **single dialog**:

- a **drawn calendar** for the date(s) — a single month calendar; days outside the supplied window
  rendered unselectable;
- **time wheels** for the hour and minute (a snapping scrollable column per field — chosen over ±1
  steppers, which made a distant time absurd to reach); the single mode shows one hour/minute pair, the
  **range mode shows two** — a **From time** pair and an **Until time** pair;

and a single confirmation commits everything. The time SHALL NOT be typed. The Material 3 `DatePicker` /
`TimePicker` SHALL NOT be used anywhere; the drawn calendar, the wheels, and the `Popup` live inside the
components module (per the Material 3 containment rule). The component SHALL surface the current value(s)
in a readable form.

In **range mode** the single-month calendar SHALL let the user tap a **start day**, then an **end day**,
highlighting the inclusive span between them; a **third tap resets** to a new start day (clearing the
span); and a **same-day range** is expressed by tapping the same day twice. Tapping a new day span SHALL
change **only the dates** and **preserve** the current From/Until wheel times. Selection SHALL be
**constrained to the window** where one is supplied: days and times outside `[min, max]` render greyed and
unselectable, and an **`until` before `from`** SHALL be blocked (unreachable in the UI). Where **no window**
is supplied (the create surface), selection is unconstrained **except** that `until` MUST still be after
`from`.

#### Scenario: The date/time component signature is appearance-free
- **WHEN** the public signature of the date/time picker is inspected
- **THEN** it carries only the current value(s), a change callback, an enabled flag, and an optional selectable window (`[min, max]`, either end absent) — no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Date and time are picked in one hand-drawn dialog
- **WHEN** the user opens the picker
- **THEN** a single `Popup` shows the drawn calendar and the time wheels together, and one confirmation commits everything

#### Scenario: No Material 3 date/time picker is used
- **WHEN** module dependencies and imports are inspected
- **THEN** no Material 3 `DatePicker` / `TimePicker` / clock dial appears anywhere; the drawn calendar, the time wheels, and the `Popup` live only inside the components module, never in any screen module or `App*` signature

#### Scenario: The window floors and ceilings the calendar
- **WHEN** the picker is given a selectable window `[min, max]`
- **THEN** days before `min` and after `max` render unselectable, and the caller additionally coerces a confirmed value into the window (a day-grain calendar cannot forbid an earlier or later hour on a boundary day)

#### Scenario: Range mode picks a span with dual handles and two time wheels
- **WHEN** the user opens the picker in range mode and taps a start day then an end day
- **THEN** the inclusive span between them highlights, the dialog shows a **From time** wheel pair and an **Until time** wheel pair, and one confirmation reports the new `[from, until]` value

#### Scenario: A new day span preserves the wheel times
- **WHEN** the user has set From and Until times and then taps a new start/end day span
- **THEN** only the dates change and both wheel times are preserved

#### Scenario: A third tap resets to a new start, and same-day is two taps of one day
- **WHEN** a start and end day are already selected and the user taps a third day
- **THEN** the selection resets to that day as the new start with the span cleared
- **WHEN** the user taps the same day twice
- **THEN** the range is that single day (`from` and `until` on the same date)

#### Scenario: Selection is constrained to the window and blocks an inverted range
- **WHEN** range mode is given a window `[min, max]`
- **THEN** days and times outside `[min, max]` render greyed and unselectable, and an `until` before the `from` is blocked so an invalid pick is unreachable

#### Scenario: Unconstrained create range still requires end after start
- **WHEN** range mode is given no window (the create surface)
- **THEN** any day/time is selectable except that the confirmed `until` MUST be after `from`

#### Scenario: Picking a date and time reports the new value
- **WHEN** the user opens the picker and picks a date and a time (or a range)
- **THEN** it invokes the change callback with the newly-picked value(s) and shows them

#### Scenario: Disabled input rejects changes
- **WHEN** the picker is rendered with `enabled = false`
- **THEN** it does not open and does not invoke the change callback

### Requirement: App event start-date row component

The design system SHALL provide an `App*` **event date-range section** component (superseding the earlier
start-date section and the still-earlier bare label-plus-affordance row), which the create screen composes
to display and edit an event's **`[start, end]` date range** alongside a stated consequence. Its signature
SHALL expose data-and-meaning parameters only: the current range as a pair of **plain, platform-neutral
date-time values** (not a Material 3 type), a change callback invoked with the newly-picked range, and a
**note** string stating the range's consequence. It MUST NOT expose appearance parameters (colors, text
styles, shapes, elevations) or a `Modifier` parameter, and **no Material 3 type may appear in its
signature**.

The component SHALL render the current range as a **readable label** with an **edit affordance beside it**,
and activating that affordance SHALL open the design system's date/time picker **in range mode**. It SHALL
render a **live humanized duration hint** derived from the current range (e.g. "Event lasts 5 days")
alongside the supplied consequence note. The range is **required** — there is no unset/`null` state,
because an event always has a start and an end.

Rendering the label, the edit affordance, the duration hint, and the note as one component (rather than
composing them at the screen) is what keeps the arrangement — and the "the range is a consequence, here is
what it means" — a **convention** owned by the design system rather than a layout each screen re-derives.

#### Scenario: The date-range section signature is appearance-free
- **WHEN** the public signature of the event date-range section is inspected
- **THEN** it carries only the current `[start, end]` date-time range, a change callback, and a note string — no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The section shows the range, an edit affordance, the duration hint, and the consequence note
- **WHEN** the date-range section is rendered with a range and a note
- **THEN** it displays that range in a readable form with an edit affordance, a live humanized duration hint (e.g. "Event lasts 5 days"), and the note stating the range's consequence

#### Scenario: Editing opens the range picker and reports the new range
- **WHEN** the user activates the edit affordance and picks a start day, an end day, and the two times
- **THEN** the component invokes its change callback with the newly-picked `[start, end]` range and displays it, its duration hint updating to match

### Requirement: App cutoff-preset selector component

The design system SHALL provide the `App*` capture-date cutoff selector as a **range preset selector**
(superseding the earlier single-cutoff choice rows and the still-earlier two-preset segmented control),
which the join screen's Share section composes to choose a capture-date **`[from, until]` range** within
the event window. It SHALL present **two handles**: a **From** handle and an **Until** handle, each a
**sealed semantic value** (per the runtime-data-variants rule). From members are **`EventStart`** / **`Now`**
/ **`Custom`**; Until members are **`EventEnd`** / **`Custom`**. Its signature SHALL expose data-and-meaning
parameters only: the selected From choice, the selected Until choice, a selection callback per handle, a
callback for a Custom pick per handle, a flag for whether the **`Now`** choice is **available**, and the
event **window** (`[startsAt, endsAt]` as plain, platform-neutral date-time values — the floor and ceiling
the caller enforces). It MUST NOT expose appearance parameters or a `Modifier`, and **no Material 3 type may
appear in its signature**.

The component SHALL render the two handles as **two separate grouped sub-lists** — one per handle, each in
its own **recessed sub-section well**, each well headed by a **caption** ("share from" / "share until")
placed **above** that well on the embedding card's surface, at the card's own text inset. The selector
SHALL render those wells **itself**; the embedding section does not wrap it in one. Two bounds are two
decisions, and a single well with a caption dropped between the groups makes the boundary between them the
weakest seam in the control. Within a group the choices SHALL remain **stacked rows** — each with its
option name, a one-line consequence, and a trailing checkmark on the chosen one. The selector as a whole
SHALL still be **embeddable** and **not** a card of its own, so both groups sit inside the Share section's
card (share and "from when / until when" are one decision surface). Each caption SHALL be visually
**subordinate** to the row labels it heads and SHALL introduce no new colour or type token, and SHALL be
exposed as an accessibility **heading**, so assistive technology can navigate between the two groups —
the navigation the visual split creates.

Selecting a **Custom** row SHALL open the design system's date+time picker **directly**,
constrained to the event window; the component SHALL NOT restate the chosen instants in the rows (the
embedding section's own value line is the single statement of the resulting range). Confirming the picker
commits the choice (clamped to the window); cancelling SHALL leave the previous selection untouched. When
the **`Now`** choice is **unavailable** — the present is outside the event window (`now < startsAt` or
`now > endsAt`) — it SHALL be rendered **disabled** rather than hidden, so the control's shape does not
change between events.

#### Scenario: The range selector is appearance-free with two sealed handles
- **WHEN** the public signature of the range preset selector is inspected
- **THEN** it carries a From selection (sealed `EventStart` / `Now` / `Custom`), an Until selection (sealed `EventEnd` / `Custom`), per-handle selection and Custom-pick callbacks, a `Now`-available flag, and the event window — and no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: Each handle is its own captioned group
- **WHEN** the From and Until rows are rendered inside the Share section
- **THEN** each handle's rows render in their own recessed well, with that handle's caption above its own well and no rows of the other handle inside it, and the embedding section wraps the selector in no well of its own

#### Scenario: The group captions are headings subordinate to the rows they head
- **WHEN** the two group captions render
- **THEN** each is exposed as an accessibility heading, is visually quieter than the row labels beneath it, and introduces no new colour or type token

#### Scenario: The handles embed and do not restate the instants
- **WHEN** the From and Until groups are rendered inside the Share section
- **THEN** each handle shows its options with a trailing checkmark on the selected one, embedded (no card of their own), and never restate the resulting range instants the section's value line already carries

#### Scenario: Custom opens the window-constrained picker directly and only its confirm commits
- **WHEN** the user selects a `Custom` row on either handle
- **THEN** the date+time picker opens immediately, constrained to the event window; confirming it commits the picked (clamped) value via that handle's Custom-pick callback and selects `Custom`, and cancelling leaves the previous selection untouched

#### Scenario: An unavailable Now choice is disabled, not hidden
- **WHEN** the From rows are rendered with the `Now` choice marked unavailable (the present is outside the event window)
- **THEN** the `Now` row renders disabled and does not invoke the selection callback on tap, and the control keeps all its rows

### Requirement: App switch-header section and its sub-levels

The design system SHALL provide an `App*` **switch-header section** — a card whose header is a title plus
an on/off switch, with a content slot for consequence lines — and the two secondary-level building blocks
it composes with: a **recessed sub-section well** (holding **a group of** a section's second-level rows —
a section MAY compose **more than one** well when its second-level rows fall into distinct groups, as the
range preset selector's From and Until groups do) and a **standalone minor section**. It SHALL also
provide a **section note** (a muted, one-line consequence) and
a **section value** (the section's single bold statement). Every signature SHALL be appearance-free (a
title, a checked state, a change callback, a content slot — no colors, shapes, text styles, `Modifier`, or
Material 3 type).

The section header's **whole row** SHALL be the single toggle target, carrying `Role.Switch`, so assistive
technology announces exactly one on/off switch per section (two live targets in one row double-fire and
read as a dead control). The switch itself SHALL be **hand-drawn** to iOS metrics, not the Material 3
`Switch` (whose thicker, outlined-thumb track reads as Android and whose default off colours invert in
dark mode); the drawn switch is drawing only, the enclosing row owning the gesture and the semantics. The
two-level grammar — the switch turns the section on, the checkmark rows in the recessed well or wells
configure it — SHALL be uniform, so the screen's idioms are a hierarchy, not a mix. Several wells in one
section SHALL remain the **same** second level (a group heading is not a third level of control). The
recess SHALL be achieved by contrast against the card surface using the frozen palette (no new colour).

#### Scenario: The section header is one Role.Switch tap target with a drawn switch
- **WHEN** a switch-header section renders
- **THEN** the whole header row is one toggle target carrying `Role.Switch`, the switch is hand-drawn (not the Material 3 `Switch`), and assistive technology announces a single on/off switch

#### Scenario: The section building blocks are appearance-free
- **WHEN** the public signatures of the switch section, the sub-section well, the minor section, the section note, and the section value are inspected
- **THEN** each carries only text/state/callback/content parameters — no colors, shapes, text styles, `Modifier`, or Material 3 type

#### Scenario: The sub-section recesses without a new colour
- **WHEN** a section's second-level rows render inside the sub-section well
- **THEN** the well recesses by contrast against the card surface using the frozen palette, in both light and dark, introducing no new colour token

#### Scenario: A section composes more than one well without adding a level
- **WHEN** a section's second-level rows fall into distinct groups and render in more than one recessed well
- **THEN** each group renders in its own well with its own caption, and every row stays at the same second level — the switch remains the only first-level control in the section

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

