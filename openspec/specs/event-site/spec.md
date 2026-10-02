# event-site Specification

## Purpose
Serves the web visitor: anyone who opens an event's invite link where SnapSync is not installed — on
Android, on a computer, or on an iPhone without the app. Whether or not the visitor ever installs SnapSync,
the photos the members shared are theirs to keep, so the link opens a page that names the event and lets them save
all of its photos in one download, with no app and no account, alongside a way to get SnapSync. How an
invite link reaches the app where it is installed, and what to do after installing, is capability
`join-event`; what this page may reveal or record is capability `privacy-security`.
Decision record: changes/archive/2026-07-21-web-event-download
## Requirements
### Requirement: An invite link without the app opens the event's page
Opening an event's invite link in a browser where SnapSync does not claim it SHALL show a page naming the
event and how many photos are ready to download. The page SHALL work in any modern browser on any
platform, with no install, no account and no sign-in.

#### Scenario: A guest on Android opens the invite
- **WHEN** a guest opens a valid invite link on an Android phone
- **THEN** a page shows the event's name and the number of photos available

#### Scenario: A guest on a computer opens the invite
- **WHEN** a guest opens a valid invite link in a desktop browser
- **THEN** the same event page is shown

### Requirement: The page always offers the download and the app
The event page SHALL always offer both a control to download all of the event's photos as one zip file
and a link to get SnapSync from the App Store, whatever device or browser the visitor uses. Once SnapSync
is published on Google Play, the page SHALL also offer a link to get it there, beside the App Store link
and on every device. Before that, while SnapSync is in a closed test on Google Play that anyone may join,
the page SHALL instead offer, in the same place and on every device, the steps to get it there as a
tester: join the testers' group, opt in as a tester on Google Play, and install from Google Play, the last
being the link to get it there. Otherwise the page SHALL NOT offer Google Play.

#### Scenario: Both actions are offered
- **WHEN** the event page is shown for an event with photos
- **THEN** it offers "download all photos (zip)" and "Get SnapSync"

#### Scenario: Both stores are offered once SnapSync is on Google Play
- **WHEN** SnapSync is published on Google Play and a visitor opens an event's page on any device
- **THEN** the page offers SnapSync on both the App Store and Google Play, with no tester steps

#### Scenario: The tester steps are offered during the closed test
- **WHEN** SnapSync is in an open-to-join closed test on Google Play, not yet published, and a visitor opens an event's page on any device
- **THEN** the page offers the App Store, and beside it the three steps to join the test, ending in the link to get SnapSync on Google Play

#### Scenario: The tester steps are offered for an invalid link too
- **WHEN** SnapSync is in an open-to-join closed test on Google Play and a visitor opens an invalid or expired invite link
- **THEN** the page says the link is invalid or expired and still offers the App Store and the steps to join the test

#### Scenario: No Google Play offer before publication
- **WHEN** SnapSync is neither published on Google Play nor in an open-to-join closed test there, and a visitor opens an event's page
- **THEN** the page offers the App Store only

### Requirement: The zip holds every photo the members have shared
The downloaded zip SHALL contain every photo and video the event's members have shared that has finished
arriving in the event — including those shared by members who have since left — each as its original file,
under its original name, with same-named files kept apart by renaming rather than one replacing another. A
Live Photo SHALL be saved as its still image and, beside it, its motion video, the two under the same name
apart from their extensions, so the pair stays together when opened again; a renamed Live Photo SHALL keep
its two files under the same new name. The zip SHALL be named after the event. Which photos are in the event,
and when a photo is withdrawn from it, is capability `photo-sharing`.

#### Scenario: A full download
- **WHEN** a visitor downloads an event whose members have shared 40 photos and videos, none of them Live
  Photos
- **THEN** the zip holds 40 files, each under its original file name, and is named after the event

#### Scenario: A Live Photo
- **WHEN** the event holds a Live Photo whose still is "IMG_4471.HEIC"
- **THEN** the zip holds "IMG_4471.HEIC" and its motion video "IMG_4471.MOV"

#### Scenario: Two photos with the same name
- **WHEN** two members' photos carry the same original file name
- **THEN** both are in the zip, one of them under a distinguishing name

#### Scenario: Two Live Photos with the same name
- **WHEN** two members' Live Photos carry the same original file name
- **THEN** both pairs are in the zip, and the renamed pair's still and video share their new name

### Requirement: The download shows its progress and can be retried
The zip SHALL be prepared in the visitor's browser. While it is being prepared the download control SHALL
be disabled and the page SHALL show how far it has got; the zip SHALL then be saved like any browser
download. If the download fails, the page SHALL say so and the visitor SHALL be able to try again. An event
too large for the browser's memory SHALL fail with a message saying a very large event may exceed it.

#### Scenario: Progress while downloading
- **WHEN** a visitor starts the download
- **THEN** the control is disabled and the page shows progress through the photos until the zip is saved

#### Scenario: A failed download
- **WHEN** the connection drops partway through a download
- **THEN** the page says the download failed and the download control is available again

### Requirement: Getting the app does not interrupt a download
Following either store's "Get SnapSync" link while a download is in progress SHALL NOT cancel or restart
the download.

#### Scenario: The visitor taps Get SnapSync mid-download
- **WHEN** a visitor follows "Get SnapSync" while the zip is being prepared
- **THEN** the App Store opens separately and the download continues

#### Scenario: The visitor follows Google Play mid-download
- **WHEN** a visitor follows the Google Play link while the zip is being prepared
- **THEN** Google Play opens separately and the download continues

### Requirement: An invalid or expired link says so
The page SHALL say the link is invalid or expired when the link is malformed or its event no longer
exists — including an event whose photos were deleted because it finished or reached the end of its
lifetime (capability `event-lifetime`). It SHALL then suggest asking the host for a fresh link, still
offer "Get SnapSync", and offer no download.

#### Scenario: A truncated link
- **WHEN** a visitor opens an invite link whose event part is cut off or corrupted
- **THEN** the page says the link is invalid or expired and offers no download

#### Scenario: An event that has been deleted
- **WHEN** a visitor opens the invite link of an event that has been deleted
- **THEN** the page says the link is invalid or expired and offers no download

#### Scenario: A finished event
- **WHEN** a visitor opens the invite link of an event whose members all received its photos and whose
  photos were then deleted, before its 30 days are up
- **THEN** the page says the link is invalid or expired and offers no download

### Requirement: An empty or unreachable event is explained
When the event exists but no photos have been shared to it yet, the page SHALL say so and offer no
download. When the event cannot be loaded because of a network or service problem, the page SHALL ask the
visitor to check their connection and reload, and SHALL NOT claim the link is invalid.

#### Scenario: No photos yet
- **WHEN** a visitor opens the invite link of an event no one has shared a photo to yet
- **THEN** the page says no photos have been shared yet and offers no download

#### Scenario: Offline visitor
- **WHEN** a visitor opens a valid invite link and the event cannot be loaded because the connection fails
- **THEN** the page asks them to check their connection and reload, and does not call the link invalid

