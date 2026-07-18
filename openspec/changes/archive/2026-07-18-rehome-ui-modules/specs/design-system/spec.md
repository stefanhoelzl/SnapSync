# design-system — delta for rehome-ui-modules

## MODIFIED Requirements

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
