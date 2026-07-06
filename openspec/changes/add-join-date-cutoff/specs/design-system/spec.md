## ADDED Requirements

### Requirement: App date/time input component

The design system SHALL provide an `App*` date/time input component — the app's first temporal input —
that the join screen composes from to collect a capture-date cutoff. Its signature SHALL expose
data-and-meaning parameters only: the current value as a **plain, platform-neutral date-time value**
(not a Material 3 type — e.g. a simple domain/`kotlinx-datetime` local date-time, or `null` for
unset), a change callback invoked with the newly-picked value, and an enabled flag. It MUST NOT expose
appearance parameters (colors, text styles, shapes, elevations) or a `Modifier` parameter, and **no
Material 3 type may appear in its signature**. The Material 3 `DatePicker` and `TimePicker` (and any
dialog scaffolding) SHALL be **contained inside** the component, per the Material 3 containment rule.
The component SHALL let the user pick both a date and a time, and SHALL surface the current value in a
readable form; it SHALL NOT itself impose date bounds (bounds, defaults, and any "shortcut" action are
the caller's concern).

#### Scenario: The date/time component signature is appearance-free
- **WHEN** the public signature of the date/time component is inspected
- **THEN** it carries only the current date-time value, a change callback, and an enabled flag — no
  colors, text styles, shapes, elevations, or `Modifier`, and no Material 3 type

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
