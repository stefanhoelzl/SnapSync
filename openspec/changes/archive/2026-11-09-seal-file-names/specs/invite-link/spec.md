# Spec Delta

## ADDED Requirements

### Requirement: The service keeps an encrypted event's photo names only encrypted
In an encrypted event, the name each photo had on its sender's phone SHALL reach SnapSync's service, and be stored and
logged there, only encrypted under the event's key and bound to that one photo. Like the photo itself, it then opens
only on a member's phone or in the browser of someone holding the whole invite (capability `event-site`). A member who
receives the photo SHALL still get it under its sender's name, exactly as in a plain event (capability
`receiving-photos`). A photo whose stored name does not open under the event's key SHALL reach no member's library;
every other photo of the event SHALL still arrive. A plain event's photo names SHALL stay readable to the service.

#### Scenario: The stored photos do not reveal their names
- **WHEN** someone with access to SnapSync's stored photos and logs, but not the invite, reads an encrypted event's
  photos
- **THEN** they can tell how many photos there are and when each was taken, but not the name any photo had on its
  sender's phone

#### Scenario: A received photo keeps its sender's name
- **WHEN** a member of an encrypted event receives another member's photo named "IMG_4471.HEIC"
- **THEN** it arrives in their library under that name with its SnapSync mark, as it would from a plain event

#### Scenario: A photo whose name does not open
- **WHEN** a photo's stored name in an encrypted event does not open under the event's key
- **THEN** that photo reaches no member's library, and the event's other photos still arrive
