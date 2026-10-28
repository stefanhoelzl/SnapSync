# Spec Delta

## MODIFIED Requirements

### Requirement: Turning the album on gathers what is already there

The album SHALL be filled with the photos the device already holds for the event whenever it is turned
on — at a join, a rejoin, a switch, a saved change of settings, or photo access becoming usable while the
app runs: on iPhone own photos currently shared to it and photos already received from it; on Android the
photos already received from it, which are moved out of the camera folder into the album. Photos received for
a different event SHALL NOT be added. On iPhone, photos this device shared before the join that the new event
also covers SHALL be added once the device has looked at them, which MAY be shortly after the join rather
than at once. Gathering SHALL never add a photo twice and SHALL NOT make the member wait. On Android a received
photo the app can no longer move — one saved before the app was reinstalled — SHALL stay in the camera folder.

#### Scenario: Turning the album on later
- **WHEN** an iPhone member who joined without the album turns it on in settings
- **THEN** the photos they already shared and received in this event are added to a new album

#### Scenario: On Android, turning the album on later moves received photos into it
- **WHEN** an Android member who joined without the album, and has received photos of this event, turns it on in settings
- **THEN** those photos move from the camera folder into the new album, each appearing once in the gallery, and their own photos stay in the camera folder

#### Scenario: A photo from another event stays out
- **WHEN** the device holds a photo received in an earlier event that is not part of this event
- **THEN** it is not added to this event's album

#### Scenario: A photo shared before the join arrives shortly after
- **WHEN** an iPhone member joins with the album on, and a photo they shared to an earlier event lies inside
  this event's range
- **THEN** it appears in the new album after the device's first pass over the library

#### Scenario: Gathering twice changes nothing
- **WHEN** the album is gathered again over an unchanged event
- **THEN** every photo is in the album once

