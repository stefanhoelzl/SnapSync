# Spec Delta

## MODIFIED Requirements

### Requirement: The app menu is one tap away on every screen
Every screen SHALL show a menu button beside the app's name that opens the app menu, except while the event
settings are open and while a join or a create is in progress — there the menu SHALL NOT be offered: during a join
or a create the button SHALL NOT be shown, and while the settings are open over the joined screen, tapping that
screen closes the settings instead. The
menu SHALL hold, first and set apart from the rest, the switch whether photos may use mobile data with a note saying
what it currently means (capability `mobile-data`); then, set apart, "Report a problem" (capability
`privacy-security`); then links to SnapSync's website and its Privacy Policy (capability `web-site`); and, last, the app's version and build
number, which is shown and not tappable. Following a link SHALL open the page in the browser, leaving the app where
it was. Flipping the switch SHALL leave the menu open. Closing the menu — by tapping outside it, its close button, swiping
it away or going back — SHALL leave the screen exactly as it was before the menu opened, apart from what the switch
changed.

#### Scenario: Opening the menu while joined
- **WHEN** a joined member taps the menu button
- **THEN** the menu opens and shows the mobile-data switch with its note, "Report a problem", the website, the
  Privacy Policy and the app's version and build number

#### Scenario: The menu before joining
- **WHEN** someone with no event opens the app
- **THEN** the menu button is shown and opens the same menu, the mobile-data switch included

#### Scenario: Reading the Privacy Policy
- **WHEN** a member taps the Privacy Policy in the menu
- **THEN** the policy opens in the browser, and returning to SnapSync shows the screen they left

#### Scenario: Editing event settings
- **WHEN** the member has the event settings open and taps where the menu button is on the joined screen above them
- **THEN** the settings close and no menu opens

#### Scenario: A join in progress
- **WHEN** a join or a create is in progress
- **THEN** no menu button is shown until it has finished or failed

#### Scenario: Closing the menu
- **WHEN** the member opens the menu and then taps outside it
- **THEN** the menu closes and the screen is as it was

#### Scenario: Closing the menu with its close button
- **WHEN** the member opens the menu and then taps its close button
- **THEN** the menu closes and the screen is as it was

#### Scenario: Flipping the mobile-data switch
- **WHEN** a member opens the menu and turns the mobile-data switch off
- **THEN** the menu stays open, the switch is off and its note says photos are shared and received only on Wi-Fi
