## MODIFIED Requirements

### Requirement: The service records how photos are delivered
The service SHALL keep a delivery record of every request it is sent. For a request from a genuine SnapSync app it
SHALL record which phone sent it (by its device identifier, requirement "No account and no personal identity"),
the app's version and build number, the phone's operating system and its version, its model, whether it is a release or a
development build, the photo access the app has, the kind of network the
phone is on and whether it may use mobile data for SnapSync, whether power saving is on, and whether the system
lets the app work in the background. For the photos the app shares and receives it SHALL record when each photo
was taken, when the app found it, when it was queued, sent and became available to the members, when it was
withdrawn, when each member
was told about it, when each member's app downloaded it and added it to their library, why the app acted at each
step (it was opened, woken for a new photo, joining, given photo access, changed its settings, checking whether
the event is finished, or a periodic background check), how often and from where an upload was resumed, how much
was sent, whether a photo arrived before the receiving member joined, and when a member had shared or received
everything. It SHALL record when events are created, joined, left, completed and deleted, and how much each
read of an event's photo list was given. For a request that is not a genuine app's, it SHALL record only that it
was not an app's, and when. The record SHALL NOT hold the photos, an event's name, the network address of a phone
or browser, or anything that identifies the person using the app. The record SHALL be visible to the operator
only, SHALL NOT be shown to members or visitors, and SHALL be kept for 30 days from the request it describes —
whether or not the event's photos have been deleted — and then deleted.

#### Scenario: A member's app is woken for a new photo
- **WHEN** another member's photo arrives and this member's app is woken and reads the photo list
- **THEN** the operator can see that this phone read the list because it was woken for a new photo

#### Scenario: A photo travels from one member to another
- **WHEN** a member takes a photo during an event and it reaches another member's library
- **THEN** the operator can see when it was taken, found, sent, announced, downloaded and added to the other
  member's library, and why the apps acted at each step

#### Scenario: The record outlives the event's photos
- **WHEN** an event's photos are deleted
- **THEN** the delivery record of its requests stays until 30 days after each request, and is deleted then

#### Scenario: The record holds nothing that identifies a person
- **WHEN** the operator reads the delivery record
- **THEN** it shows device identifiers, phone and app facts and times, and no photo, no event name, no network
  address and nothing that identifies the person using the app

#### Scenario: Members do not see each other's activity
- **WHEN** a member uses the app during an event
- **THEN** nothing in the app shows when or why other members' apps read, shared or received the event's photos


#### Scenario: Two builds of one version
- **WHEN** two phones run different builds that carry the same app version
- **THEN** the operator can tell from their requests which build each phone runs
