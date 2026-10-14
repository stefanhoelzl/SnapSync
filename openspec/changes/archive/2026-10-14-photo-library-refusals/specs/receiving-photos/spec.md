# Spec Delta

## MODIFIED Requirements

### Requirement: Other members' photos arrive in the photo library automatically

A receiving member SHALL get every photo the other members of their event share, once it is complete
(capability `photo-sharing`), saved into their phone's photo library without any action: the Photos library
on iPhone and, on Android, the camera folder, where received photos sit in the gallery's timeline beside the
member's own camera photos — or, while the event album is on, the album's own folder, which gallery apps show
as an album named after the event and whose photos sit in the same timeline (capability `event-album`). The
app SHALL have no gallery of its own. A member joining an event already under way SHALL receive its existing
photos straight away, whether or not the event's start date has passed. This device's own photos SHALL never
be downloaded back to it. Receiving SHALL work the same under full and under limited photo access (capability
`photo-access`). A received file the
phone's photo library cannot open SHALL NOT hold up the others: it is not saved, every other photo still
arrives, and the member is shown as up to date without it (capability `sync-status`).

#### Scenario: Receiving under limited access
- **WHEN** a member who granted limited access receives photos from others
- **THEN** they are saved into the library exactly as under full access

#### Scenario: A co-member's photo appears in the library
- **WHEN** another member of the event shares a photo
- **THEN** it appears in this member's photo library without them doing anything

#### Scenario: On Android, a received photo is in the camera roll
- **WHEN** an Android member with the event album off receives a photo another member took
- **THEN** it appears among the photos in their phone's camera folder, in any gallery app

#### Scenario: On Android with the album on, a received photo is in the event's album
- **WHEN** an Android member with the event album on receives a photo another member took
- **THEN** it appears in the event's album and in the gallery's timeline, in any gallery app, and not in the camera folder

#### Scenario: Joining a live event brings its history
- **WHEN** a member joins an event that already holds photos from others
- **THEN** those photos start arriving immediately

#### Scenario: Own photos are not received back
- **WHEN** this device's own shared photos are part of the event
- **THEN** none of them is downloaded or saved again on this device

#### Scenario: A photo the phone cannot open
- **WHEN** another member shares a file this phone's photo library cannot open, together with an ordinary photo
- **THEN** the ordinary photo appears in the library, the unreadable one does not, and the member is shown as up to date
