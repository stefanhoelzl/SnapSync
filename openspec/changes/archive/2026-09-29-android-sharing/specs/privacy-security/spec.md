## MODIFIED Requirements

### Requirement: Only a genuine SnapSync app can change an event
Creating an event, joining it, adding or withdrawing photos, renaming it and leaving it SHALL be possible
only from a genuine, unmodified SnapSync app on a genuine device: an Apple device, or an Android phone whose
hardware vouches for the app and which runs its maker's verified system. A browser, a script, a modified app,
an emulator, or an Android phone with an unlocked bootloader or a modified system SHALL be refused, even
when it holds the invite link; holding the link grants reading only. A genuine app SHALL act only for its own
device: only the device that shared a photo can withdraw it.

#### Scenario: A script tries to add a photo
- **WHEN** a program that is not a genuine SnapSync app tries to add a file to an event whose invite link it holds
- **THEN** it is refused and the event's members never receive the file

#### Scenario: One member's app tries to withdraw another member's photos
- **WHEN** a genuine SnapSync app acts in an event on behalf of a device other than its own
- **THEN** it is refused, and the other member's shared photos are unchanged

#### Scenario: A browser holding the link
- **WHEN** a web visitor with the invite link uses the event page
- **THEN** they can download the photos but cannot add, remove or rename anything

#### Scenario: A genuine Android phone joins
- **WHEN** a guest with an Android phone running its maker's system joins with the genuine SnapSync app
- **THEN** they can join and share like an iPhone member

#### Scenario: An Android phone with an unlocked bootloader
- **WHEN** a SnapSync app on an Android phone with an unlocked bootloader tries to join an event
- **THEN** it is refused, and the event is unchanged
