# Spec Delta

## ADDED Requirements

### Requirement: Only the whole invite opens an event's photos
Every event this version creates SHALL be encrypted with a key of its own, made on the host's phone and carried to
everyone else only inside the event's invite (capability `join-event`). Its photos SHALL be stored only encrypted
under that key, and SHALL be opened only on a member's phone or in the browser of someone holding the whole invite
(capability `event-site`). SnapSync's service SHALL never be given the event's key. The one exception is an iPhone
whose system uploads photos in the background on the app's behalf: there, for each photo, the service SHALL receive
a key that opens only that photo, use it to encrypt that photo as it arrives, and keep neither the key nor the
unencrypted photo. The key SHALL NOT appear in any log, failure report or user-sent bug report. A member's phone
SHALL never upload an encrypted event's photo unencrypted: when it cannot read the key, it uploads nothing.

#### Scenario: The stored photos cannot be opened without the invite
- **WHEN** someone with access to SnapSync's storage, but not the invite, reads an encrypted event's stored photos
- **THEN** they cannot open any of them

#### Scenario: The service never learns the event's key
- **WHEN** a host creates an event and its members join, share and receive photos
- **THEN** the event's key never reaches SnapSync's service, except as one photo's own key for a photo the iPhone's
  system uploads in the background

#### Scenario: A bug report carries no key
- **WHEN** a member of an encrypted event sends a detailed bug report
- **THEN** the report does not contain the event's key

#### Scenario: A phone that cannot read the key uploads nothing
- **WHEN** a member's phone cannot read the event's key
- **THEN** none of their photos is uploaded until it can, and none is ever uploaded unencrypted

### Requirement: The invite handed to Google Play carries its key
When a visitor follows an encrypted event's page's Google Play button (as "The event's identity goes only to
SnapSync's own service" allows), the invite the page hands to Google Play SHALL include the event's key, so the app
installed from it can join the event. Google Play then holds the whole invite. The page SHALL hand it over only
through that button and only from a page opened with the whole invite.

#### Scenario: Following the button from an encrypted event's page
- **WHEN** a visitor on an encrypted event's page, opened with its whole invite, follows its Google Play button
- **THEN** Google Play receives that whole invite, key included, and no other third party receives any of it

#### Scenario: A page without the key hands over no key
- **WHEN** a visitor on an encrypted event's page opened without its key follows its Google Play button
- **THEN** Google Play receives no key
