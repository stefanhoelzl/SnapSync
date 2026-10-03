# Spec Delta

## MODIFIED Requirements

### Requirement: The member decides separately whether to share, whether to receive, and whether to keep an album
The join screen SHALL show the event's name and two switches — share my photos, and receive everyone's
photos — both on by default, and SHALL NOT make the user pick a named mode. Neither switch SHALL ever
flip the other. With sharing on, the screen SHALL state what is never shared (screenshots, screen
recordings, GIFs and pictures saved from chat apps, capability `photo-sharing`) and the capture range
being shared; with it off, it SHALL say that nothing of the user's leaves the phone and hide the range.
With both switches off, Join SHALL be disabled and the reason stated beside it. The screen SHALL also offer
the event-album choice (capability `event-album`), whose note names only the photos the current switches would
collect — on Android only received photos ever are — and the choice whether photos may use mobile data
(capability `mobile-data`), switched on, whose note says, when it is off, that photos are sent and received
only on Wi-Fi.

#### Scenario: Both switches start on
- **WHEN** the join screen has loaded an event
- **THEN** it shows the event's name, "share my photos" on with the shared range and the exclusions, and "receive everyone's photos" on

#### Scenario: Receive only
- **WHEN** the user turns sharing off and joins
- **THEN** none of the user's photos are shared to the event, and the event's photos arrive in their library (capability `receiving-photos`)

#### Scenario: Share only
- **WHEN** the user turns receiving off and joins
- **THEN** the user's photos in range are shared and none of the event's photos arrive in their library

#### Scenario: Both off blocks Join with a reason
- **WHEN** the user turns both switches off
- **THEN** Join is disabled, a line above it explains that a membership that neither shares nor receives does nothing, and neither switch turns itself back on

#### Scenario: The album choice on Android
- **WHEN** the join screen has loaded an event on an Android phone
- **THEN** it offers the two switches, the range, and the album choice switched on, whose note names the photos they receive

#### Scenario: The mobile-data choice starts on
- **WHEN** the join screen has loaded an event
- **THEN** it offers the choice to use mobile data for photos, switched on

#### Scenario: Joining with mobile data off
- **WHEN** the user turns the mobile-data choice off and joins
- **THEN** the screen has said that photos are sent and received only on Wi-Fi, and the membership's photos travel only on Wi-Fi (capability `mobile-data`)
