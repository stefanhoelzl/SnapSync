## MODIFIED Requirements

### Requirement: The album is offered on by default and declinable in one tap

On iPhone, the join surface (capability `join-event`) SHALL offer the event album as its own choice,
separate from the choices to share and to receive, switched on unless the member unchecks it; joining
without touching it SHALL create the album. The surface SHALL tell the member which photos the album will
collect for their current choices — the photos they share, the photos they receive, both, or nothing — and,
when unchecked, that no album will be created. The choice SHALL be changeable later (capability
`manage-membership`).

#### Scenario: Joining without touching the album choice
- **WHEN** an iPhone member joins without changing the album choice
- **THEN** an album for the event is created in their library

#### Scenario: Declining the album
- **WHEN** an iPhone member unchecks the album choice and joins
- **THEN** no album is created and no photo is placed in one

#### Scenario: The explanation follows the switches
- **WHEN** an iPhone member turns sharing off and keeps receiving on the join surface
- **THEN** the album choice explains that it will collect the photos they receive

## ADDED Requirements

### Requirement: An Android member has no event album

On Android the app SHALL NOT offer the event album — neither on the join screen nor in settings — and SHALL
NOT create an album or place any photo in one. The member's own photos stay where their camera saved them,
and received photos arrive in the camera folder (capability `receiving-photos`).

#### Scenario: Joining on Android
- **WHEN** an Android member joins an event
- **THEN** the join screen offers no album choice, and no album for the event appears in their gallery

#### Scenario: Settings on Android
- **WHEN** an Android member opens the settings of their membership
- **THEN** no album choice is offered
