## REMOVED Requirements

### Requirement: Event settings change as they are made, without leaving, until the photos are deleted
**Reason**: Its range bounds were held to the event's start and end; a changed range now follows the join rule
and may reach outside them.
**Migration**: Replaced by "Event settings change as they are made, without leaving, until the event's photos are deleted", identical but for the range rule and a range equal to the event's dates
showing as from the event's start.

## ADDED Requirements

### Requirement: Event settings change as they are made, without leaving, until the event's photos are deleted
Until the event's photos are deleted (capability `event-lifetime`), the joined screen SHALL offer a settings action
that opens the same choices as the join screen — share and receive switches, the capture range and the
album — showing the membership's current settings, over the joined screen, which stays partly visible above them. A
range equal to the event's own dates SHALL show as from the event's start, and any other as a custom range.
Each change SHALL apply as it is made — a switch when it is flipped, the range when a preset is chosen or a
picked range is confirmed — with no Save or Cancel; changes made one after another SHALL apply in order, the
last one standing. Sharing and receiving MAY both be off: the member then stays in the event and nothing is
shared or received. The settings SHALL close by swiping them down, by going back, or by tapping the joined
screen above them, and closing SHALL change nothing. A changed range SHALL follow the same rule as at join
(capability `join-event`): at most 30 days long, overlapping the event's dates, and MAY reach outside them.
Changing settings SHALL keep the member in the event and SHALL work offline. Once the event's
photos have been deleted, settings SHALL NOT be offered.

#### Scenario: Settings open pre-filled
- **WHEN** a member who shares and receives with the album on opens settings
- **THEN** both switches and the album are on and the range shows the one they joined with

#### Scenario: An Android membership from before the album opens with it off
- **WHEN** an Android member who joined before Android had the event album opens settings
- **THEN** the album choice is offered and is off

#### Scenario: A change applies without a Save
- **WHEN** the member turns the album on and closes settings by swiping them down
- **THEN** the album is on, and reopening settings shows it on

#### Scenario: Closing changes nothing further
- **WHEN** the member opens settings and closes them by tapping the joined screen above, without changing anything
- **THEN** their settings are exactly as before

#### Scenario: The last of several quick changes stands
- **WHEN** the member flips the receive switch off, on and off again in quick succession
- **THEN** receiving is off

#### Scenario: Both directions may be off
- **WHEN** a receive-only member turns receiving off
- **THEN** the member stays in the event, sharing and receiving nothing, and the joined screen says so

#### Scenario: A range narrower than the event shows as custom
- **WHEN** a member who joined sharing from a time after the event's start opens settings
- **THEN** the range shows as a custom range with that start and the event's end

#### Scenario: A start before the event's start is kept
- **WHEN** the member picks a start before the event's start and confirms it
- **THEN** the membership shares from that start, and the settings say that photos from before the event are
  shared too

#### Scenario: Settings change offline
- **WHEN** the member changes a setting while the device is offline
- **THEN** the change takes effect and the member remains in the event

#### Scenario: Settings still change after the end
- **WHEN** a member turns receiving on a week after the event's range ended, before its photos were deleted
- **THEN** the change applies and the event's photos start arriving

#### Scenario: A deleted event offers no settings
- **WHEN** a member opens the joined screen of an event whose photos were deleted
- **THEN** no settings action is offered

#### Scenario: Settings hold no mobile-data choice
- **WHEN** a member opens settings
- **THEN** no mobile-data choice is offered there; it is the device's, in the app menu (capability `mobile-data`)
