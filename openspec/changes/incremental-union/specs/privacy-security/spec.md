## MODIFIED Requirements

### Requirement: A web visitor leaves no trace in the event
Viewing the event page or downloading an event's photos SHALL NOT make the visitor a member, SHALL NOT take one
of the event's device places, and SHALL be invisible to the members. The service SHALL record only that a
browser read the event's photo list and when (requirement "The service records who reads an event's photo
list"); it SHALL NOT record the visitor's address, browser or anything else that identifies them.

#### Scenario: Many visitors download
- **WHEN** twenty people download an event's photos through its invite link
- **THEN** the event's membership and its remaining device places are unchanged, and no member sees them

#### Scenario: What a visit leaves behind
- **WHEN** a web visitor opens an event page and downloads its photos
- **THEN** the operator can see that a browser read the event's photo list at that time, and nothing that
  identifies the visitor

## ADDED Requirements

### Requirement: A link to a single photo lasts only as long as the photo is shared
A link to a single stored photo, as handed to a member's app or to the event page, SHALL work only while that
photo is part of the event: once its member withdraws it (capability `photo-sharing`) or the event's photos
are deleted (capability `event-lifetime`), the link SHALL NOT serve it. Such a link names its event, so
whoever holds one SHALL be able to reach what the event's invite link reaches (requirement "The invite link
is the key to an event"); neither the app nor the event page SHALL show it to the user.

#### Scenario: A copied photo link after the photo is withdrawn
- **WHEN** someone copies the address of a single photo out of the event page, and its member then deletes
  that photo before the event closes
- **THEN** opening the copied address no longer serves the photo

#### Scenario: A copied photo link after the event's photos are deleted
- **WHEN** someone opens a copied single-photo address after the event's photos have been deleted
- **THEN** the photo is not served

#### Scenario: A copied photo link while the photo is shared
- **WHEN** someone opens a copied single-photo address 8 days later, while the photo is still part of the
  event
- **THEN** the photo is served, as it would be to anyone holding the event's invite link

### Requirement: The service records who reads an event's photo list
For each event the service SHALL keep a record of when each shared photo became available to the members and
when it was withdrawn, and of every read of the event's photo list: for a read by a genuine SnapSync app, which
install read it (by its random install identifier), why the app read it (it was opened, woken for a new
photo, joining, given photo access, changed its settings, checking whether the event is finished, or a
periodic background check), and how much it was given; for any other read, only that it was not an app's.
The record SHALL be visible to the operator only, SHALL NOT be shown to members or visitors, and SHALL be
deleted together with the event's photos (capability `event-lifetime`).

#### Scenario: A member's app is woken for a new photo
- **WHEN** another member's photo arrives and this member's app is woken and reads the photo list
- **THEN** the operator can see that this install read the list because it was woken for a new photo

#### Scenario: The record ends with the event
- **WHEN** an event's photos are deleted
- **THEN** its record of photos and reads is deleted with them

#### Scenario: Members do not see each other's reads
- **WHEN** a member uses the app during an event
- **THEN** nothing in the app shows when or why other members read the event's photo list

## REMOVED Requirements

### Requirement: Links to individual photos expire
**Reason**: A photo link is now a stable address on SnapSync's service that redirects to a freshly signed
storage link per download, so it no longer stops working on a clock. Its lifetime is now the photo's own:
requirement "A link to a single photo lasts only as long as the photo is shared".
**Migration**: None for users. Links handed out before the change keep expiring within 7 days.
