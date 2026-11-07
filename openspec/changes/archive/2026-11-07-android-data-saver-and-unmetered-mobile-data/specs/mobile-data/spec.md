## MODIFIED Requirements

### Requirement: With mobile data off, photos wait for an unrestricted Wi-Fi
With the choice off, the member's photos SHALL NOT be uploaded or downloaded over mobile data, over
another phone's personal hotspot or any other network the phone treats as costly, or while the phone's
Low Data Mode (iPhone, on the network in use) or Data Saver (Android, on every network, Wi-Fi included)
is in force. Data Saver SHALL NOT hold photos back when the member allowed SnapSync unrestricted data in
it. Mobile data SHALL count as mobile data even while the carrier does not charge for it. Those photos
SHALL wait and SHALL be sent and received, in the background and without the member opening the app, as
soon as the phone is on a network none of these apply to; after the member switches Data Saver off they
SHALL travel in the background too, though not necessarily at once. A transfer already under way when the
member switches Data Saver on MAY still finish. Waiting SHALL never lose a photo or
bring one twice; it only delays it. The choice SHALL NOT extend the event: a photo still waiting when the
event's photos are deleted (capability `event-lifetime`) is not shared to it.

#### Scenario: On mobile data nothing is transferred
- **WHEN** a member with mobile data off takes an in-range photo while on mobile data, and another member shares a photo meanwhile
- **THEN** neither photo is transferred until the phone joins an unrestricted Wi-Fi

#### Scenario: A hotspot counts as mobile data
- **WHEN** a member with mobile data off is connected to another phone's personal hotspot
- **THEN** no photo is uploaded or downloaded over it

#### Scenario: Low Data Mode counts as mobile data
- **WHEN** a member with mobile data off is on a Wi-Fi network for which the phone's Low Data Mode or Data Saver is on
- **THEN** no photo is uploaded or downloaded over it

#### Scenario: Data Saver holds photos on an ordinary Wi-Fi
- **WHEN** an Android member with mobile data off switches Data Saver on while on their home Wi-Fi, with photos to share and to receive
- **THEN** no photo is uploaded or downloaded until Data Saver is off, and once it is the photos travel without the app being opened

#### Scenario: SnapSync allowed unrestricted data
- **WHEN** an Android member with mobile data off has Data Saver on but allowed SnapSync unrestricted data, and is on an ordinary Wi-Fi
- **THEN** their photos are uploaded and downloaded over that Wi-Fi

#### Scenario: Mobile data the carrier does not charge for
- **WHEN** an Android member with mobile data off is on mobile data the carrier currently does not charge for, such as an unlimited allowance in a particular area
- **THEN** no photo is uploaded or downloaded over it

#### Scenario: Reaching Wi-Fi sends what waited
- **WHEN** a member with mobile data off reaches an unrestricted Wi-Fi with photos waiting in both directions, and does not open the app
- **THEN** the waiting photos are uploaded and the waiting received photos arrive in their library
