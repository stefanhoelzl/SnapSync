## MODIFIED Requirements

### Requirement: A member changes what they share and receive without leaving
The joined screen SHALL offer a settings action that opens the same choices as the join screen —
share and receive switches, the capture range, and the album — pre-filled with the membership's current
settings under the event's name. A saved range equal to the event's whole window
SHALL show as the whole event, and any other as a custom range. Save SHALL apply all changes at once, without a confirmation dialog; Cancel SHALL
discard them. Both switches off SHALL disable Save with the reason stated. Changed range bounds SHALL
stay within the event's start and end. Changing settings SHALL keep the member in the event and SHALL
work offline.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: Cancel discards changes
- **WHEN** the member changes several settings and taps Cancel
- **THEN** their settings are exactly as before

#### Scenario: A range narrower than the event shows as custom
- **WHEN** a member who joined sharing from a time after the event's start opens settings
- **THEN** the range shows as a custom range with that start and the event's end

#### Scenario: A widened start is held to the event's start
- **WHEN** the member picks a start before the event's start and saves
- **THEN** the membership shares from the event's start

#### Scenario: Settings change offline
- **WHEN** the member saves new settings while the device is offline
- **THEN** the change takes effect and the member remains in the event
