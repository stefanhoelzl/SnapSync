## MODIFIED Requirements

### Requirement: The notification token is used only to deliver new photos
The token Apple gives the app for waking it SHALL be used only to tell that device that new photos are
ready in its event, or, once, that its event has closed (capability `event-lifetime`).

#### Scenario: Another member shares a photo
- **WHEN** another member's photo arrives in the event
- **THEN** the token is used to wake this device for it, and for nothing else

#### Scenario: The event closes
- **WHEN** the device's event closes
- **THEN** the token is used once to wake the device for it
