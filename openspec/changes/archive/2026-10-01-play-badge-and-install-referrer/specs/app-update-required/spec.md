## MODIFIED Requirements

### Requirement: The notice says what to install and where

The update notice SHALL name the oldest version that will work when the server states one, and
otherwise SHALL say that a newer version is needed. It SHALL offer a button that opens SnapSync's page in
the store the app is distributed through, named as that store: the App Store on iPhone, Google Play on
Android. When the installed app knows no store page for SnapSync, for example on Android before SnapSync is
published on Google Play, the notice SHALL offer no button and SHALL still say that an update is required.

#### Scenario: Minimum version named
- **WHEN** the server refuses the app and states that version 0.12 or newer is required
- **THEN** the notice says SnapSync 0.12 or newer is needed

#### Scenario: Opening the App Store
- **WHEN** the member taps the App Store button on the notice on an iPhone
- **THEN** SnapSync's App Store page opens

#### Scenario: Opening Google Play
- **WHEN** the member taps the Google Play button on the notice on an Android phone
- **THEN** SnapSync's Google Play page opens

#### Scenario: No store page known
- **WHEN** the notice is shown on an Android phone whose installed SnapSync knows no Google Play page
- **THEN** the notice says an update is required and offers no store button
