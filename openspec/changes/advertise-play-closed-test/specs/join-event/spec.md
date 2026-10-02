## MODIFIED Requirements

### Requirement: Without the app, the invite leads to a store and back
Opening an invite on a device without SnapSync SHALL show the event's web page (capability `event-site`),
which offers SnapSync on the App Store and, whenever that page offers Google Play (to testers during a
closed test, or once SnapSync is published there), on Google Play. Because iOS
does not hand a link over through an installation, an iPhone user who installs the app from there SHALL
reach the event by opening the original invite again.

An Android user who installs SnapSync by following that page's Google Play button, on the same phone, SHALL
find the app open on the join screen for that event at its first launch, without opening the invite again.
The join screen SHALL behave exactly as for a tapped invite: nothing is joined until they confirm. The
carried invite SHALL be acted on at most once per installation. An installation that did not come through
an invite's page SHALL open the app as usual, on no join screen and with no damaged-invite report. Opening
the original invite again SHALL keep working on both platforms.

#### Scenario: Installing, then reopening the invite joins
- **WHEN** a guest without SnapSync opens an invite on an iPhone, installs the app from the page, and then taps the same invite again
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: Installing from Google Play opens the join screen
- **WHEN** a guest without SnapSync opens an invite on an Android phone, follows the page's Google Play button, installs SnapSync and opens it for the first time
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: Becoming a tester from an invite's page opens the join screen
- **WHEN** a guest without SnapSync opens an invite on an Android phone during the closed test, follows the page's steps to become a tester, installs SnapSync from the page's Google Play button and opens it for the first time
- **THEN** SnapSync opens on the join screen for that event

#### Scenario: The carried invite opens only once
- **WHEN** that guest joins or dismisses the join screen, and later opens SnapSync again
- **THEN** the carried invite's join screen does not return

#### Scenario: An ordinary installation opens no join screen
- **WHEN** someone installs SnapSync from Google Play without coming from an invite's page, and opens it
- **THEN** the app opens as usual, with no join screen and no report of a damaged invite

#### Scenario: Installing from a computer carries no invite
- **WHEN** a visitor follows the Google Play button on an invite's page in a desktop browser and installs SnapSync onto their phone from there
- **THEN** the app opens without a join screen, and opening the invite on the phone opens its join screen
