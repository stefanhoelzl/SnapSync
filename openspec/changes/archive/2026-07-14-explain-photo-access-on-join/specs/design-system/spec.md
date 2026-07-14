## ADDED Requirements

### Requirement: App explainer component

The design system SHALL provide an `AppExplainer` semantic component for a full-screen explanation
that precedes a consequential system prompt: a neutral indicator, a headline, and a body of one or
more short paragraphs.

Its signature SHALL carry **content only** — a headline and an ordered list of paragraphs — and no
appearance parameters: no `Modifier`, no color, no shape, no text style, and no Material 3 type
(consistent with "Semantic-only components").

The component SHALL render the **neutral** photo-library indicator (`StatusIndicator.Photos`) — an ask,
not a fault — and SHALL NOT use a warning or error treatment. Paragraph spacing SHALL be owned by the
component, not by the calling screen, so no caller composes raw geometry (consistent with "Semantic
containers own convention-bearing arrangement").

The component SHALL NOT own its actions. The calling screen SHALL pin its confirm and cancel actions in
the same bottom action cluster every other surface on that screen uses, so the buttons align with the
screen's other phases.

#### Scenario: The explainer renders a neutral ask
- **WHEN** `AppExplainer` renders
- **THEN** the skin draws the neutral photo-library glyph, not a warning or error treatment

#### Scenario: The explainer carries no appearance parameters
- **WHEN** a screen composes `AppExplainer`
- **THEN** it passes only a headline and paragraphs, and the signature exposes no `Modifier`, color, shape, text style, or Material 3 type

#### Scenario: The explainer owns its paragraph arrangement
- **WHEN** a screen supplies more than one paragraph
- **THEN** the component spaces them, and the screen composes no raw geometry to do so
