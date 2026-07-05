## MODIFIED Requirements

### Requirement: App status-line component

The design system SHALL provide a semantic status-line component that renders the joined-layer sync
health from a single sealed semantic value (e.g. `InSync` / `Syncing(uploadArrow, downloadArrow)` /
`NeedsAccess`), where each arrow state is one of `Hidden` / `Static` / `Pulsing`. Per the
semantic-only rule it SHALL expose **no** appearance parameters (no `Modifier`, color, shape, or text
style) — callers pass only the health value and, for the attention state, an `onClick`. The component
SHALL animate a `Pulsing` arrow and render a `Static` arrow without motion, SHALL render the attention
(`NeedsAccess`) state as the **only** variant carrying a background, and SHALL respect reduced-motion
preferences. It SHALL surface **no numeric counts**.

For the `Syncing` value the component SHALL choose the label from the arrows' activity: when **any**
shown arrow is `Pulsing` the label SHALL read **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is `Pulsing` the label SHALL read **"Synchronization pending…"**. These label strings
are owned by the component (as "In sync" already is). The Material 3 skin SHALL tint a `Static` arrow
with a muted/neutral color (gray) and a `Pulsing` arrow with the brand **primary** accent; these color
mappings are skin-local and SHALL NOT appear on any `App*` signature.

#### Scenario: Pulsing arrow drives the ongoing label
- **WHEN** the status line is given `Syncing(upload = Pulsing, download = Hidden)`
- **THEN** it shows the upload arrow animating in the brand primary, no download arrow, and the
  "Synchronization ongoing…" label, with no counts and no exposed appearance parameters

#### Scenario: Static-only arrows drive the pending label
- **WHEN** the status line is given `Syncing(upload = Static, download = Hidden)`
- **THEN** it shows the upload arrow static in a muted gray (no motion), no download arrow, and the
  "Synchronization pending…" label

#### Scenario: Only the attention state has a background
- **WHEN** the status line renders `InSync` or `Syncing`
- **THEN** it is flat (no background); **WHEN** it renders `NeedsAccess`, it carries a background and
  invokes `onClick` on tap

## ADDED Requirements

### Requirement: Status accents unified on the brand primary

The Material 3 skin SHALL render the live/complete status accents with the brand **primary** color
rather than a distinct green: the status-line `Pulsing` arrow and the LED-style `StatusIndicator`
active/complete dots (e.g. `Complete`) SHALL use primary. No standalone green accent SHALL remain for
these status indicators. These are skin-local color choices and SHALL NOT introduce any appearance
parameter on an `App*` signature.

#### Scenario: The complete indicator uses primary, not green
- **WHEN** the skin renders the `Complete` `StatusIndicator` dot or a `Pulsing` status-line arrow
- **THEN** it is tinted with the brand primary color, and no green accent is used
