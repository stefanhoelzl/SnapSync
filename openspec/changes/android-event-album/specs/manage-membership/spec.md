## ADDED Requirements

### Requirement: A member changes their settings without leaving
Until the event closes (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range, and the
album — pre-filled with the membership's current settings under the event's name. A saved
range equal to the event's whole window SHALL show as the whole event, and any other as a custom range.
Save SHALL apply all changes at once, without a confirmation dialog; Cancel SHALL discard them. Both
switches off SHALL disable Save with the reason stated. Changed range bounds SHALL stay within the event's
start and end. Changing settings SHALL keep the member in the event and SHALL work offline. Once the event
has closed, settings SHALL NOT be offered and the membership's settings SHALL stay as they were at the
close.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: An Android membership from before the album opens with it off
- **WHEN** an Android member who joined before Android had the event album opens settings
- **THEN** the album choice is offered and is off

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

#### Scenario: A closed event's settings are fixed
- **WHEN** a member opens the joined screen of an event that has closed
- **THEN** no settings action is offered, and what they share and receive stays as it was

## MODIFIED Requirements

### Requirement: Settings explain what a change does
The settings screen SHALL show the live count of photos that will be shared (as on the join screen,
capability `join-event`). It SHALL state that sharing less stops listing those photos to the event while
anyone who already received them keeps them, and that photos the member received stay; it SHALL NOT
suggest that narrowing deletes or recalls photos from other members. Turning the album on SHALL say that the
photos already synced are collected too — on Android, the photos already received.

#### Scenario: Narrowing is described honestly
- **WHEN** the member opens settings
- **THEN** the screen says that sharing less stops listing those photos to the event and that anyone who already received them keeps them

#### Scenario: Album-on mentions photos already synced
- **WHEN** an iPhone member turns the album on in settings
- **THEN** the screen says the album also collects the photos already synced

#### Scenario: On Android album-on mentions photos already received
- **WHEN** an Android member turns the album on in settings
- **THEN** the screen says the album also collects the photos already received, and that their own photos stay in the camera folder

## REMOVED Requirements

### Requirement: A member changes what they share and receive without leaving

**Reason**: Replaced by the requirement of the new name, which offers the album in settings on Android too; the scenario "Settings on Android offer no album" no longer holds.
**Migration**: None: the other settings and scenarios are carried over unchanged. Android memberships joined before this change open with the album off.
