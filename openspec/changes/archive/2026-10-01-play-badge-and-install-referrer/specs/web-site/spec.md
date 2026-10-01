## MODIFIED Requirements

### Requirement: A public landing page describes SnapSync
Opening SnapSync's web address in any browser SHALL show a landing page that explains what SnapSync does
and offers a link to get the app from the App Store. Once SnapSync is published on Google Play, the page
SHALL also offer a link to get it there, beside the App Store link and on every device. Until then, the
page SHALL NOT offer Google Play. It SHALL need no account, no app and no sign-in.

#### Scenario: A visitor opens the site
- **WHEN** someone opens SnapSync's web address in a browser
- **THEN** they see the landing page describing SnapSync, with a link to the App Store

#### Scenario: Both stores are offered once SnapSync is on Google Play
- **WHEN** SnapSync is published on Google Play and someone opens SnapSync's web address
- **THEN** the landing page links to SnapSync on both the App Store and Google Play

#### Scenario: The site opens in the browser even with the app installed
- **WHEN** someone with SnapSync installed opens the site's address (not an invite link) on their iPhone
- **THEN** the landing page opens in the browser rather than switching to the app

### Requirement: Links to other sites never replace the SnapSync page
Every link from the site to another site (App Store, Google Play, issue tracker, email, Apple's licence
terms) SHALL open separately, leaving the SnapSync page open where it was.

#### Scenario: Following the App Store link
- **WHEN** a visitor follows the App Store link from any page of the site
- **THEN** the App Store opens separately and the SnapSync page stays open

#### Scenario: Following the Google Play link
- **WHEN** a visitor follows the Google Play link from any page of the site
- **THEN** Google Play opens separately and the SnapSync page stays open
