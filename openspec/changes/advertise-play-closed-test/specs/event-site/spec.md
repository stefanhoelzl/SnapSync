## MODIFIED Requirements

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
