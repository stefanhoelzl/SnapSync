## MODIFIED Requirements

### Requirement: A public landing page describes SnapSync
Opening SnapSync's web address in any browser SHALL show a landing page that explains what SnapSync does
and offers a link to get the app from the App Store. Once SnapSync is published on Google Play, the page
SHALL also offer a link to get it there, beside the App Store link and on every device. Before that, while
SnapSync is in a closed test on Google Play that anyone may join, the page SHALL instead offer, in the
same place and on every device, the steps to get it there as a tester: join the testers' group, opt in as
a tester on Google Play, and install from Google Play, the last being the link to get it there. Otherwise
the page SHALL NOT offer Google Play. It SHALL need no account, no app and no sign-in.

#### Scenario: A visitor opens the site
- **WHEN** someone opens SnapSync's web address in a browser
- **THEN** they see the landing page describing SnapSync, with a link to the App Store

#### Scenario: Both stores are offered once SnapSync is on Google Play
- **WHEN** SnapSync is published on Google Play and someone opens SnapSync's web address
- **THEN** the landing page links to SnapSync on both the App Store and Google Play, with no tester steps

#### Scenario: The tester steps are offered during the closed test
- **WHEN** SnapSync is in an open-to-join closed test on Google Play, not yet published, and someone opens SnapSync's web address
- **THEN** the landing page offers the App Store, and beside it the three steps to join the test, ending in the link to get SnapSync on Google Play

#### Scenario: Following the steps installs SnapSync
- **WHEN** a visitor with an Android phone joins the testers' group, opts in, and follows the Google Play link on the same Google account their phone's Play Store uses
- **THEN** Google Play offers SnapSync for installation on that phone

#### Scenario: No Google Play offer otherwise
- **WHEN** SnapSync is neither published on Google Play nor in an open-to-join closed test there, and someone opens SnapSync's web address
- **THEN** the landing page offers the App Store only

#### Scenario: The site opens in the browser even with the app installed
- **WHEN** someone with SnapSync installed opens the site's address (not an invite link) on their iPhone
- **THEN** the landing page opens in the browser rather than switching to the app
