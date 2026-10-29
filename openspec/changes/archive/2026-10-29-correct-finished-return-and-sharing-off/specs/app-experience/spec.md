## MODIFIED Requirements

### Requirement: The app keeps its place across backgrounding

Returning to the app from the background SHALL show the same screen the member left, with any open
surface and any half-typed text intact, and SHALL never present a blank or corrupted screen. An event that
finished meanwhile does not change that: the app never leaves an event on its own (capability
`event-lifetime`).

#### Scenario: Returning to an open settings surface
- **WHEN** the member opens settings, switches to another app, and comes back later
- **THEN** the settings surface is still open as they left it

#### Scenario: Returning after hours in the background
- **WHEN** the app was woken in the background several times and the member opens it hours later
- **THEN** it renders normally, never blank

#### Scenario: Returning after the event finished
- **WHEN** the member left the joined screen open, the event finished while the app was in the background,
  and the member returns and opens another event's invite
- **THEN** no switch question is asked and the new event's join screen is shown, as "Opening another event's
  invite asks before switching" decides (capability `invite-link`); the member joins only when they confirm
