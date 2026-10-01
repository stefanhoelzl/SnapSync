## MODIFIED Requirements

### Requirement: The page always offers the download and the app
The event page SHALL always offer both a control to download all of the event's photos as one zip file
and a link to get SnapSync from the App Store, whatever device or browser the visitor uses. Once SnapSync
is published on Google Play, the page SHALL also offer a link to get it there, beside the App Store link
and on every device. Until then, the page SHALL NOT offer Google Play.

#### Scenario: Both actions are offered
- **WHEN** the event page is shown for an event with photos
- **THEN** it offers "download all photos (zip)" and "Get SnapSync"

#### Scenario: Both stores are offered once SnapSync is on Google Play
- **WHEN** SnapSync is published on Google Play and a visitor opens an event's page on any device
- **THEN** the page offers SnapSync on both the App Store and Google Play

#### Scenario: No Google Play offer before publication
- **WHEN** SnapSync is not yet published on Google Play and a visitor opens an event's page
- **THEN** the page offers the App Store only

### Requirement: Getting the app does not interrupt a download
Following either store's "Get SnapSync" link while a download is in progress SHALL NOT cancel or restart
the download.

#### Scenario: The visitor taps Get SnapSync mid-download
- **WHEN** a visitor follows "Get SnapSync" while the zip is being prepared
- **THEN** the App Store opens separately and the download continues

#### Scenario: The visitor follows Google Play mid-download
- **WHEN** a visitor follows the Google Play link while the zip is being prepared
- **THEN** Google Play opens separately and the download continues
