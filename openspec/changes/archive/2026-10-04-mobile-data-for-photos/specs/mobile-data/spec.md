# Spec Delta

## Purpose

Serves a member on a limited or expensive data plan: it promises that, when they choose so for a
membership, their event photos are sent and received only over an unrestricted Wi-Fi network while the
rest of SnapSync keeps working on any network, and that photos held back are never lost — they travel,
in the background, as soon as such a Wi-Fi is available.

## ADDED Requirements

### Requirement: The member chooses per membership whether photos may use mobile data
Each membership SHALL carry the member's choice whether its photos may be sent and received over mobile
data. The choice SHALL be on — photos may use mobile data — unless the member turns it off, so a member
who never touches it shares and receives exactly as without the choice. It SHALL be made on the join
screen (capability `join-event`) and changed in the membership's settings until the event closes
(capability `manage-membership`). It SHALL belong to the membership: joining another event starts from the
default again. It SHALL apply the same way to sharing and to receiving.

#### Scenario: Untouched, photos use any network
- **WHEN** a member joins without changing the mobile-data choice and takes a photo while on mobile data
- **THEN** the photo is shared over mobile data, as it would be without the choice

#### Scenario: A new event starts from the default
- **WHEN** a member who turned mobile data off for one event later joins another event
- **THEN** the new event's join screen offers the choice switched on

### Requirement: With mobile data off, photos wait for an unrestricted Wi-Fi
With the choice off, the member's photos SHALL NOT be uploaded or downloaded over mobile data, over
another phone's personal hotspot or any other network the phone treats as costly, or over a network on
which the phone's Low Data Mode (iPhone) or Data Saver (Android) is in force. Those photos SHALL wait and
SHALL be sent and received, in the background and without the member opening the app, as soon as the
phone is on a Wi-Fi network none of these apply to. Waiting SHALL never lose a photo or bring one twice;
it only delays it. The choice SHALL NOT extend the event: a photo still waiting when the event closes
(capability `event-lifetime`) is not shared to it.

#### Scenario: On mobile data nothing is transferred
- **WHEN** a member with mobile data off takes an in-range photo while on mobile data, and another member shares a photo meanwhile
- **THEN** neither photo is transferred until the phone joins an unrestricted Wi-Fi

#### Scenario: A hotspot counts as mobile data
- **WHEN** a member with mobile data off is connected to another phone's personal hotspot
- **THEN** no photo is uploaded or downloaded over it

#### Scenario: Low Data Mode counts as mobile data
- **WHEN** a member with mobile data off is on a Wi-Fi network for which the phone's Low Data Mode or Data Saver is on
- **THEN** no photo is uploaded or downloaded over it

#### Scenario: Reaching Wi-Fi sends what waited
- **WHEN** a member with mobile data off reaches an unrestricted Wi-Fi with photos waiting in both directions, and does not open the app
- **THEN** the waiting photos are uploaded and the waiting received photos arrive in their library

### Requirement: Everything but photo transfers keeps working on any network
The mobile-data choice SHALL govern only the transfer of photos. Joining, leaving, renaming the event,
saving settings, the counts and status on the joined screen, telling the event which photos the member
shares, being woken for new photos, and sending a diagnostic report SHALL keep working on mobile data with
the choice off.

#### Scenario: Joining on mobile data
- **WHEN** a member on mobile data joins an event with the choice turned off
- **THEN** the join completes and the joined screen shows the event and its counts

#### Scenario: Renaming on mobile data
- **WHEN** a member with mobile data off renames the event while on mobile data
- **THEN** the rename succeeds for every member

### Requirement: A change applies to transfers that start afterwards
Changing the choice SHALL govern every photo transfer that starts after the change is saved. A transfer
already under way SHALL keep the rule it started with: after turning mobile data off, a transfer already
under way MAY still finish over mobile data; after turning it on, a photo already waiting for Wi-Fi MAY
keep waiting for it, while every other photo uses mobile data at once. No transfer SHALL be cancelled or
lost because of a change.

#### Scenario: Turning mobile data on sends the rest at once
- **WHEN** a member with mobile data off and many photos waiting turns the choice on while on mobile data and saves
- **THEN** photos start uploading and arriving over mobile data without waiting for Wi-Fi, apart from the few already handed over to wait

#### Scenario: Turning mobile data off stops new transfers
- **WHEN** a member turns the choice off while on mobile data and saves
- **THEN** no photo transfer starts over mobile data from then on
