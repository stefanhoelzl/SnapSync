## MODIFIED Requirements

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

The component SHALL render each handle's choices as **stacked, embeddable rows** — each with its option
name, a one-line consequence, and a trailing checkmark on the chosen one — **not** as a card of its own, so
the rows embed inside the Share section's card (share and "from when / until when" are one decision
surface). Selecting a **Custom** row SHALL open the design system's date+time picker **directly**,
constrained to the event window; the component SHALL NOT restate the chosen instants in the rows (the
embedding section's own value line is the single statement of the resulting range). Confirming the picker
commits the choice (clamped to the window); cancelling SHALL leave the previous selection untouched. When
the **`Now`** choice is **unavailable** — the present is outside the event window (`now < startsAt` or
`now > endsAt`) — it SHALL be rendered **disabled** rather than hidden, so the control's shape does not
change between events.

#### Scenario: The range selector is appearance-free with two sealed handles
- **WHEN** the public signature of the range preset selector is inspected
- **THEN** it carries a From selection (sealed `EventStart` / `Now` / `Custom`), an Until selection (sealed `EventEnd` / `Custom`), per-handle selection and Custom-pick callbacks, a `Now`-available flag, and the event window — and no colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

#### Scenario: The handles embed and do not restate the instants
- **WHEN** the From and Until rows are rendered inside the Share section
- **THEN** each handle shows its options with a trailing checkmark on the selected one, embedded (no card of their own), and never restate the resulting range instants the section's value line already carries

#### Scenario: Custom opens the window-constrained picker directly and only its confirm commits
- **WHEN** the user selects a `Custom` row on either handle
- **THEN** the date+time picker opens immediately, constrained to the event window; confirming it commits the picked (clamped) value via that handle's Custom-pick callback and selects `Custom`, and cancelling leaves the previous selection untouched

#### Scenario: An unavailable Now choice is disabled, not hidden
- **WHEN** the From rows are rendered with the `Now` choice marked unavailable (the present is outside the event window)
- **THEN** the `Now` row renders disabled and does not invoke the selection callback on tap, and the control keeps all its rows
