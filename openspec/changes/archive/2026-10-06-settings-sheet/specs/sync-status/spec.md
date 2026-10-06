## MODIFIED Requirements

### Requirement: One status line in a fixed priority

The joined screen SHALL show exactly one status line. When several conditions hold at once it SHALL show the
first that applies, in this order: the app is still reading its state; the member neither shares nor receives
(both switched off in the event's settings, capability `manage-membership`) and no work remains in either
direction — the line then reads "Not sharing or receiving"; photo access missing; no usable network (as "The
app says when it cannot reach the network" requires); the event has not started; the device cannot be
verified; then "Up to date" or work in progress. Limited photo access SHALL NOT count as
missing access (capability `photo-access`).

#### Scenario: Missing access outranks a future start
- **WHEN** a joined member without photo access is in an event that starts tomorrow
- **THEN** the status line asks for photo access, so they can fix it before the event begins

#### Scenario: Missing access outranks a missing network
- **WHEN** a joined member without photo access has no network
- **THEN** the status line asks for photo access

#### Scenario: A missing network outranks everything else
- **WHEN** a joined member with access has no network, in an event that has not started or while everything is
  up to date
- **THEN** the status line says the network is missing, with its cause

#### Scenario: A future start outranks progress
- **WHEN** a member with access is joined to an event that has not started
- **THEN** the status line says sharing starts with the event, whatever work is outstanding

#### Scenario: Limited access shows ordinary progress
- **WHEN** a member with limited access is joined to a started event
- **THEN** the status line shows "Up to date" or progress, never the missing-access line

#### Scenario: Neither sharing nor receiving is said plainly
- **WHEN** a joined member has switched both sharing and receiving off and nothing is left to transfer
- **THEN** the status line reads "Not sharing or receiving", without counts, even if photo access is missing, the network is gone or the event has not started

#### Scenario: Work left in switched-off directions is still shown
- **WHEN** a member switches both directions off while a photo is still uploading
- **THEN** the status line shows that upload's progress until it finishes, then reads "Not sharing or receiving"

### Requirement: Direction arrows show remaining work and live transfer

While work remains, the status line SHALL show an upload arrow when some of the member's photos are
not yet shared and a download arrow when some of the others' photos have not yet arrived. An arrow
SHALL pulse while a transfer in its direction is actually running and stay still while work waits; the
line SHALL read "Photos arriving…" when any arrow pulses, "Waiting for Wi-Fi…" when no arrow
pulses and the work waits because the member chose not to use mobile data for photos and the phone is on a
network that choice avoids (capability `mobile-data`), and "Photos queued…" otherwise. "Up to date" SHALL be shown exactly when neither arrow is shown and the member shares or receives; with both switched off and neither arrow shown the line reads "Not sharing or receiving" ("One status line in a fixed priority"). A direction the member switched
off has no work and therefore no arrow — but if the app ever does work in a switched-off direction, that
arrow SHALL be shown rather than hidden.

#### Scenario: A new photo waiting to upload
- **WHEN** the member takes an in-range photo and no upload is running yet
- **THEN** a still upload arrow appears with "Photos queued…"

#### Scenario: Photos arriving
- **WHEN** the member's uploads are done and others' photos are downloading
- **THEN** only the download arrow shows, pulsing, with "Photos arriving…"

#### Scenario: Receive-only ignores the member's own gallery
- **WHEN** a receive-only member has unshared photos in their library and all received photos have
  arrived
- **THEN** the status line reads "Up to date"

#### Scenario: Work in a switched-off direction is not masked
- **WHEN** a receive-only member's device nevertheless has uploads outstanding
- **THEN** the upload arrow is shown and the line does not read "Up to date"

#### Scenario: Photos waiting for Wi-Fi are named
- **WHEN** a member with mobile data off is on mobile data and has a photo waiting to upload
- **THEN** a still upload arrow is shown with "Waiting for Wi-Fi…"

#### Scenario: Reaching Wi-Fi replaces the waiting line
- **WHEN** that member's phone joins an unrestricted Wi-Fi while the app is open
- **THEN** the waiting line is replaced by "Photos arriving…" while the photo uploads

### Requirement: The app menu is one tap away on every screen
Every screen SHALL show a menu button beside the app's name that opens the app menu, except while the event
settings are open and while a join or a create is in progress — there the menu SHALL NOT be offered: during a join
or a create the button SHALL NOT be shown, and while the settings are open over the joined screen, tapping that
screen closes the settings instead. The
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
- **WHEN** the member has the event settings open and taps where the menu button is on the joined screen above them
- **THEN** the settings close and no menu opens

#### Scenario: A join in progress
- **WHEN** a join or a create is in progress
- **THEN** no menu button is shown until it has finished or failed

#### Scenario: Closing the menu
- **WHEN** the member opens the menu and then taps outside it
- **THEN** the menu closes and the screen is as it was
