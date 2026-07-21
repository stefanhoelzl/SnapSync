# design-system Specification

## MODIFIED Requirements

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
