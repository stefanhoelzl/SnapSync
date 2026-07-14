## ADDED Requirements

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

## MODIFIED Requirements

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

### Requirement: App status-line component

The design system SHALL provide a semantic status-line component that renders the joined-layer sync
health from a single sealed semantic value (e.g. `InSync` / `Syncing(uploadArrow, downloadArrow)` /
`NeedsAccess` / **`NotStarted(startsAt)`**), where each arrow state is one of `Hidden` / `Static` /
`Pulsing`. Per the semantic-only rule it SHALL expose **no** appearance parameters (no `Modifier`, color,
shape, or text style) — callers pass only the health value and, for the attention state, an `onClick`. The
component SHALL animate a `Pulsing` arrow and render a `Static` arrow without motion, SHALL render the
attention (`NeedsAccess`) state as the **only** variant carrying a background, and SHALL respect
reduced-motion preferences. It SHALL surface **no numeric counts**.

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

#### Scenario: Only the attention state has a background
- **WHEN** the status line renders `InSync`, `Syncing`, or `NotStarted`
- **THEN** it is flat (no background); **WHEN** it renders `NeedsAccess`, it carries a background and
  invokes `onClick` on tap
