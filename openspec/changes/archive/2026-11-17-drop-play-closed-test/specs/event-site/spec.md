# Spec Delta

## ADDED Requirements

### Requirement: The page always offers the download and both stores
The event page SHALL always offer a control to download all of the event's photos as one zip file, a link to
get SnapSync from the App Store and a link to get it from Google Play, the two store links side by side,
whatever device or browser the visitor uses.

#### Scenario: Both actions are offered
- **WHEN** the event page is shown for an event with photos
- **THEN** it offers "download all photos (zip)" and "Get SnapSync"

#### Scenario: Both stores are offered
- **WHEN** a visitor opens an event's page on any device
- **THEN** the page offers SnapSync on both the App Store and Google Play

#### Scenario: Both stores are offered for an invalid link too
- **WHEN** a visitor opens an invalid or expired invite link
- **THEN** the page says the link is invalid or expired and still offers SnapSync on both the App Store and Google Play

## REMOVED Requirements

### Requirement: The page always offers the download and the app
**Reason**: Replaced by "The page always offers the download and both stores". SnapSync is published on Google Play, so the page always offers both stores. The closed test's tester steps and the "App Store only" outcome cannot occur any more, so "The tester steps are offered during the closed test" and "No Google Play offer before publication" go, "The tester steps are offered for an invalid link too" becomes "Both stores are offered for an invalid link too", and "Both stores are offered once SnapSync is on Google Play" becomes "Both stores are offered". The CLI cannot drop scenarios from a requirement it keeps, so the requirement is renamed.
**Migration**: Tests that verify this requirement name "The page always offers the download and both stores" instead.
