## MODIFIED Requirements

### Requirement: With mobile data off, photos wait for an unrestricted Wi-Fi
With the choice off, the member's photos SHALL NOT be uploaded or downloaded over mobile data, over
another phone's personal hotspot or any other network the phone treats as costly, or over a network on
which the phone's Low Data Mode (iPhone) or Data Saver (Android) is in force. Those photos SHALL wait and
SHALL be sent and received, in the background and without the member opening the app, as soon as the
phone is on a Wi-Fi network none of these apply to. Waiting SHALL never lose a photo or bring one twice;
it only delays it. The choice SHALL NOT extend the event: a photo still waiting when the event's photos are
deleted (capability `event-lifetime`) is not shared to it.

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
