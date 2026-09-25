# event-album Specification

## Purpose

Without it, an event's photos scatter through the camera roll: the member's own shots blend into their
timeline and received photos land loose beside them. This capability gives each member an album in their
Photos library, named after the event, that gathers the event as their device holds it — the photos they
share and the photos they receive — which is also why the app needs no gallery of its own. It is on by
default and declinable in one tap, it survives leaving and rejoining, it respects an album the member
deleted, and a problem with the album never gets in the way of sharing or receiving.

Decision record: changes/archive/2026-07-08-add-event-album

## Requirements

### Requirement: The album is offered on by default and declinable in one tap

The join surface (capability `join-event`) SHALL offer the event album as its own choice, separate from
the choices to share and to receive, switched on unless the member unchecks it; joining without touching
it SHALL create the album. The surface SHALL tell the member which photos the album will collect for their
current choices — the photos they share, the photos they receive, both, or nothing — and, when unchecked,
that no album will be created. The choice SHALL be changeable later (capability `manage-membership`).

#### Scenario: Joining without touching the album choice
- **WHEN** a member joins without changing the album choice
- **THEN** an album for the event is created in their library

#### Scenario: Declining the album
- **WHEN** a member unchecks the album choice and joins
- **THEN** no album is created and no photo is placed in one

#### Scenario: The explanation follows the switches
- **WHEN** a member turns sharing off and keeps receiving on the join surface
- **THEN** the album choice explains that it will collect the photos they receive

### Requirement: The album holds the event as this device holds it

While the album is on, it SHALL contain every photo of this event that the device holds: the member's own
photos being shared to the event and the photos received from other members of this event. The album
SHALL be titled with the event's name when it is created. A received photo SHALL appear in the album the
moment it appears in the library, never loose first. An own photo SHALL be placed as soon as the device
queues it for sharing, without waiting for the upload — also when offline.

#### Scenario: Both directions land in the album
- **WHEN** a member who shares and receives takes a photo and receives one during the event
- **THEN** both are in the event's album

#### Scenario: An offline photo is already in the album
- **WHEN** a member takes an in-range photo with no network connection
- **THEN** it is placed in the album before its upload has completed

#### Scenario: A received photo is never loose
- **WHEN** a photo from another member is saved into the library
- **THEN** it is already in the album at the moment it becomes visible

#### Scenario: A photo not being shared is not placed
- **WHEN** a photo lies outside the member's capture range or is excluded (capability `photo-sharing`)
- **THEN** it is not placed in the album

### Requirement: Turning the album on gathers what is already there

The album SHALL be filled with the photos the device already holds for the event whenever it is turned
on — at a join, a rejoin, a switch, a saved change of settings, or photo access becoming usable while the
app runs: own photos currently shared to it and photos already received from it. Photos received for a
different event SHALL NOT be added. Photos this device shared before the join that the new event also
covers SHALL be added once the device has looked at them, which MAY be shortly after the join rather than
at once. Gathering SHALL never add a photo twice and SHALL NOT make the member wait.

#### Scenario: Turning the album on later
- **WHEN** a member who joined without the album turns it on in settings
- **THEN** the photos they already shared and received in this event are added to a new album

#### Scenario: A photo from another event stays out
- **WHEN** the device holds a photo received in an earlier event that is not part of this event
- **THEN** it is not added to this event's album

#### Scenario: A photo shared before the join arrives shortly after
- **WHEN** a member joins with the album on, and a photo they shared to an earlier event lies inside this
  event's range
- **THEN** it appears in the new album after the device's first pass over the library, without being
  uploaded again

#### Scenario: Gathering twice changes nothing
- **WHEN** the album is gathered again over an unchanged event
- **THEN** every photo is in the album once

### Requirement: Turning the album off keeps it

Turning the album off SHALL stop placing new photos and SHALL NOT delete the album or remove photos from
it. Turning it on again SHALL reuse the same album.

#### Scenario: Off, then on again
- **WHEN** a member turns the album off, takes photos, and turns it on again
- **THEN** the same album is used and the photos taken meanwhile are gathered into it

### Requirement: The album survives leaving and is reused on rejoin

Leaving the event SHALL NOT delete the album or its photos. Rejoining the same event with the album on
SHALL reuse the existing album. Photos SHALL be placed in the album this event created even if the member
renamed that album or another event shares its name; renaming the event (capability `manage-membership`)
SHALL NOT retitle an album already created.

#### Scenario: Rejoin reuses the album
- **WHEN** a member leaves an event and later rejoins it with the album on
- **THEN** new photos go into the same album, next to the earlier ones

#### Scenario: A renamed album keeps receiving photos
- **WHEN** a member renames the event's album in Photos
- **THEN** new event photos are still placed in it

#### Scenario: Two events with the same name
- **WHEN** a member has been in two events with the same name
- **THEN** each event's photos go to that event's own album

### Requirement: A deleted album is recreated only when the member asks

If the member deletes the album, the app SHALL NOT recreate it on its own. It SHALL create a new album
only on a deliberate opt-in: rejoining with the album on, or turning the album on in settings.

#### Scenario: A deleted album stays deleted
- **WHEN** the member deletes the event's album and more photos arrive
- **THEN** no album reappears, and the photos still arrive in the library

#### Scenario: Opting in again recreates it
- **WHEN** after deleting it the member turns the album on in settings
- **THEN** a new album is created and gathers the event's photos

### Requirement: The album is created as soon as it can be, under full or limited access

The album SHALL be created when the member joins with it on and photo access is full or limited, or as
soon as such access is granted afterwards, so it exists before the first photo is placed. A membership
that never shares or receives a photo MAY have an empty album.

#### Scenario: Created once access is granted
- **WHEN** a member joins with the album on before granting photo access, and then grants limited access
- **THEN** the album is created and photos placed from then on land in it

### Requirement: Album problems never affect sharing or receiving

A failure to create the album or to place a photo in it SHALL NOT stop, delay or repeat any upload or
download; the photo is still shared or saved to the library.

#### Scenario: A placement fails
- **WHEN** placing a photo in the album fails
- **THEN** the photo is still uploaded or saved into the library as usual
