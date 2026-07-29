## MODIFIED Requirements

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
