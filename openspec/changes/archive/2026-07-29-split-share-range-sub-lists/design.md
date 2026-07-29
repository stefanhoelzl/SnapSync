## Context

The capture-date range selector (`AppRangePresetChoices`, `:ui:components`) renders two handles — **From**
(Event start · Now · Custom) and **Until** (Event end · Custom). Both were introduced together by
`changes/archive/2026-07-22-add-event-date-range` (`D6`), whose argument was symmetry with the data model
`D5` had just given a ceiling to mirror the floor. The component emits both handles' rows into a single
`Column`; the two screens that compose it — the join surface's `ReadyLayout` and `ReconfigureScreen` —
each wrap that `Column` in one `AppSubSection` recessed well.

The result is five rows in one well, governing two different bounds, separated only by a caption drawn at
the same horizontal inset as the row labels. There is no divider or gap above "Share until", so the
boundary between the handles is the weakest seam in the group, while the seams *within* each handle carry
inset dividers.

Two constraints frame every option below:

- **Join and reconfigure are one decision surface.** `reconfigure-membership` requires the reconfigure
  surface to compose "the **same** design-system controls the join surface uses". Anything done to one is
  done to both; there is no per-surface variant without forking that contract.
- **The section's value line is the single statement of the range.** `design-system` forbids the selector
  from restating the chosen instants; the enclosing card's bold "Sharing …" line is where they appear.

## Goals / Non-Goals

**Goals:**

- Make the two bounds read as two separate objects, so a member can see at a glance that "Share from" and
  "Share until" are independent decisions.
- Give each group a caption that reads as a heading for what follows, visually and to assistive technology.
- Keep the change confined to how the existing rows are grouped: no new choices, no changed defaults, no
  new state, no signature change.

**Non-Goals:**

- **Hiding anything currently visible.** Progressive disclosure of the range (a sheet, or an in-card
  accordion behind a "Change" affordance) was explored and rejected — see Decisions.
- **Removing the Until handle** from the surface.
- **Refreshing the marketing screenshots.** `screenshots/*.png` predate the range selector entirely (they
  show the superseded single-cutoff UI), so this change neither creates nor worsens their staleness.
- Any change to defaults, clamping, phase derivation, the pickers' commit semantics, the share count, or
  the persisted membership.

## Decisions

### D1 — Two recessed wells, captions above them

Each handle gets its own `AppSubSection` well, with its caption rendered **above** the well on the
enclosing card's surface, at the card's own text inset (the one `AppSectionNote` and `AppSectionValue`
already use).

*Alternatives considered:*

- **Two wells, captions still inside each well** — splits the containers but keeps the caption aligned with
  the row labels, so it continues to read as a disabled first row. Rejected: it fixes the seam and leaves
  the ambiguity that made the caption hard to read as a heading.
- **One well with a stronger seam** (full-bleed divider + extra spacing before the second caption) —
  cheapest, introduces no container. Rejected: the groups still read as one list with a label in the
  middle, which is the actual complaint.

A caption above a rounded, recessed group is the iOS inset-grouped-list idiom (Settings.app, SwiftUI
`.insetGrouped`); a caption *inside* the container is the Material subheader idiom. This surface already
commits to the former deliberately — `AppToggleSection`'s switch is hand-drawn to iOS metrics precisely
because "these two switches are the most prominent controls on the join surface, so they are the worst
possible place for the app to read as a port".

### D2 — The selector owns both wells; the screens stop wrapping it

`AppRangePresetChoices` renders caption + `AppSubSection` twice internally. Both call sites drop their
`AppSubSection { … }` wrapper. The public signature is unchanged.

*Alternatives considered:*

- **Split into `AppFromChoices` / `AppUntilChoices`**, with each screen composing caption + well around
  each — halves a twelve-parameter signature into two coherent ones. Rejected: it moves layout authority
  into two screens that must then stay in sync by hand, and doubles the spec delta, to relieve a signature
  that is not currently a problem.
- **A per-handle parameter**, the screens calling the component twice — most churn at the call sites, least
  design-system authority over an arrangement the design system is supposed to own.

Keeping it one component is what makes "join and reconfigure change together" structural rather than
remembered. The wells are still **not a card of their own**: they remain embedded in the Share section's
card, which is the property `design-system` actually pins.

`AppSubSection` thereby loses both of its `:ui:screens` call sites and is called only from within
`:ui:components`. It **stays public**: `design-system` names it as one of the switch section's required
building blocks and pins its signature in a scenario.

### D3 — Caption steps down one type level, sentence case

The relocated caption renders one level quieter than today (`labelLarge` → `labelMedium`), in the same
muted role colour, sentence case.

*Alternatives considered:*

- **Keep `labelLarge`** — on the card surface it competes with the bold "Sharing 1 Jun – 8 Jun" value line
  for the eye; the caption is a label for a group, not a second statement.
- **Uppercase and tracked, reusing the existing eyebrow idiom** — the classic UIKit grouped-header look,
  and an idiom the app already has. Rejected: the eyebrow currently signals *identity* ("YOU'RE INVITED");
  spending it on form-group headers dilutes what it means.

No new colour or type token: the step is within the existing scale.

### D4 — The captions are accessibility headings

Each caption carries heading semantics, so a rotor jump lands on "Share from" / "Share until" — the same
navigation the visual split creates. Semantics are not appearance, so the appearance-free signature rule is
untouched.

### D5 — Nothing that is visible today becomes hidden

The split adds height to a surface that already overflows its viewport: on a 390×844 pane with sharing on,
the Receive section, the album row, and the retention line already sit below the fold. Two options that
would have recovered that space were explored and **rejected**, on the standing constraint that a control
visible today stays visible:

- **A value row opening the two lists in a sheet** — the bold "Sharing …" line becomes tappable and the
  groups move to a surface with room. It would have recovered roughly 400 px.
- **An in-card accordion**, collapsed by default behind a "Change" affordance — the same recovery without a
  new surface.

Both hide a control whose default is the **widest** legal range (the full event window), and both make the
narrowing affordance something a member must go looking for. The fold is accepted instead, because the
screen puts the decisions on the right sides of it: the **irreversible** one — Share, with its
origin-exclusions note, its bold range statement and its live shareable count — is entirely above the fold,
while what falls below it is Receive, which defaults on, is the product's premise, and carries risk in the
safe direction (other members' photos arriving in your library is what scanning the invite was for). The
body is documented to scroll beneath the pinned actions, and a sliver of the next card is visible at the
fold.

The split's own cost here is small: roughly 24 dp (the gap between the two wells plus their insets), the
captions themselves already occupying their line today.

### D6 — The Until handle stays

Removing Until from the join surface — on the argument that a wrong **From** bound uploads the existing
camera roll immediately and irreversibly, while a wrong **Until** bound costs nothing until a future photo
is taken and can be narrowed at any time (`add-event-date-range` `D7`: narrowing re-excludes, nothing is
retracted) — was explored and **rejected**. Join and reconfigure are one surface, so there is no "settings
only" to move it to; the only coherent version is deleting the handle from the product, which discards a
capability shipped days earlier to solve a layout problem. Its set-and-forget case is also real: a guest
joining a two-week trip event for one weekend states their end once instead of remembering to narrow later.

### D7 — The grouping is pinned structurally, not by eye

Each well carries a test tag; `AppRangePresetChoicesTest` asserts each caption and its rows are descendants
of their own group node. Today's tests address rows by tag and pass whether the groups are split or not, so
they pin nothing about this change. Offscreen Compose tests cannot assert the recess itself — that is
verified by eye in the forge harness (`:test:harness-driver:driveForge`, join **Ready (confirm)** preset).

## Risks / Trade-offs

- **The surface grows ~24 dp on a viewport that already overflows** → Accepted deliberately (D5), on the
  finding that the irreversible decision and its live count stay above the fold. Measured against the
  390×844 forge pane, not estimated.
- **`AppSubSection` will read as unused from `:ui:screens`** and could be mistaken for dead code → It is
  spec-required as a design-system building block and is called by the selector; its doc comment is updated
  to say a section may hold more than one.
- **A structural test can encode the current tree rather than the intent** → Assert only containment
  (caption and rows inside their own group), never the number of intermediate nodes, so a refactor of the
  well's internals does not fail the test.
- **Two wells cost two borders and two fills** on the same card → No new colour token is introduced; the
  wells reuse the existing recess treatment exactly, so the frozen palette stays frozen.

## Migration Plan

None. No persisted state, wire format, or capability contract changes; the change is confined to how two
existing groups of rows are framed, on two surfaces that compose the same component.

## Open Questions

- ~~The exact vertical rhythm (caption top/bottom spacing, the gap between the two wells).~~ **Settled**
  against the rendered forge output in both themes: the caption takes the card's existing 14 dp text inset
  with 4 dp above / 6 dp below, and the wells keep `AppSubSection`'s existing insets — which puts 16 dp
  between a well and the next caption. The starting metrics needed no adjustment, and the split turned out
  to cost essentially no net height (the captions already occupied their line).
