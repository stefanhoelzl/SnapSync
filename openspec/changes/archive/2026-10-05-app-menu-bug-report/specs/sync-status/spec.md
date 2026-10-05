## ADDED Requirements

### Requirement: The app menu is one tap away on every screen
Every screen SHALL show a menu button beside the app's name that opens the app menu, except while the event
settings are being edited and while a join or a create is in progress — there the button SHALL NOT be shown. The
menu SHALL hold, first and set apart from the rest, "Report a problem" (capability `privacy-security`); then links
to SnapSync's website and its Privacy Policy (capability `web-site`); and, last, the app's version and build
number, which is shown and not tappable. Following a link SHALL open the page in the browser, leaving the app where
it was. Closing the menu — by tapping outside it, swiping it away or going back — SHALL leave the screen exactly as
it was before the menu opened.

#### Scenario: Opening the menu while joined
- **WHEN** a joined member taps the menu button
- **THEN** the menu opens and shows "Report a problem", the website, the Privacy Policy and the app's version and
  build number

#### Scenario: The menu before joining
- **WHEN** someone with no event opens the app
- **THEN** the menu button is shown and opens the same menu

#### Scenario: Reading the Privacy Policy
- **WHEN** a member taps the Privacy Policy in the menu
- **THEN** the policy opens in the browser, and returning to SnapSync shows the screen they left

#### Scenario: Editing event settings
- **WHEN** the member has the event settings open
- **THEN** no menu button is shown

#### Scenario: A join in progress
- **WHEN** a join or a create is in progress
- **THEN** no menu button is shown until it has finished or failed

#### Scenario: Closing the menu
- **WHEN** the member opens the menu and then taps outside it
- **THEN** the menu closes and the screen is as it was
