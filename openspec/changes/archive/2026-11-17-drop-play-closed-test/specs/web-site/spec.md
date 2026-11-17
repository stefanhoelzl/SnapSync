# Spec Delta

## ADDED Requirements

### Requirement: The landing page describes SnapSync and offers both stores
Opening SnapSync's web address in any browser SHALL show a landing page that explains what SnapSync does
and offers a link to get the app from the App Store and a link to get it from Google Play, side by side and
on every device. It SHALL need no account, no app and no sign-in.

#### Scenario: A visitor opens the site
- **WHEN** someone opens SnapSync's web address in a browser
- **THEN** they see the landing page describing SnapSync, with a link to the App Store

#### Scenario: Both stores are offered
- **WHEN** someone opens SnapSync's web address, on any device
- **THEN** the landing page links to SnapSync on both the App Store and Google Play

#### Scenario: The site opens in the browser even with the app installed
- **WHEN** someone with SnapSync installed opens the site's address (not an invite link) on their iPhone
- **THEN** the landing page opens in the browser rather than switching to the app

## REMOVED Requirements

### Requirement: A public landing page describes SnapSync
**Reason**: Replaced by "The landing page describes SnapSync and offers both stores". SnapSync is published on Google Play, so the page always offers both stores. The closed test's tester steps and the "App Store only" outcome cannot occur any more, so their scenarios ("The tester steps are offered during the closed test", "Following the steps installs SnapSync", "No Google Play offer otherwise") go, and "Both stores are offered once SnapSync is on Google Play" becomes "Both stores are offered". The CLI cannot drop scenarios from a requirement it keeps, so the requirement is renamed.
**Migration**: Tests that verify this requirement name "The landing page describes SnapSync and offers both stores" instead.
