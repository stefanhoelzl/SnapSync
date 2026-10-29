# app-experience Specification

## Purpose
Serves every user on every screen: SnapSync behaves the same calm, predictable way wherever they are in it. It
promises a portrait phone app that follows the system's light or dark appearance; a first frame that is never a guess;
its place kept across backgrounding, and background wakes that show nothing; taps that work, and only once; failures
told calmly; explanations that always match the current choices; a date range that cannot be picked wrong; text entry
that stays usable while typing; the app menu one tap away; and controls that are accessible and honour reduced motion.
What each screen shows is the capability of that screen.

## Requirements

### Requirement: The app is a portrait phone app that follows the system appearance

The app SHALL run in upright portrait only on iPhone and on Android phones, never rotating, and SHALL follow the
system's light or dark appearance, with no white flash when opening in dark mode. On an Android tablet or an unfolded
foldable, the system MAY rotate it.

#### Scenario: Rotating the phone
- **WHEN** the member turns an iPhone or an Android phone to landscape
- **THEN** the app stays upright portrait

#### Scenario: Launching in dark mode
- **WHEN** the phone is in dark mode and the app is launched
- **THEN** the app opens dark without a white flash

### Requirement: The first frame is never a guessed state

The first frame the app shows SHALL be derived from what the device actually holds: a joined member
SHALL see the joined screen (with the neutral line until state is read), and a member with no event the
create screen. A read of the membership that fails temporarily — including on a locked device — SHALL
NOT drop a joined member to the create screen.

#### Scenario: Joined launch
- **WHEN** a joined member launches the app
- **THEN** the first frame is the joined screen with the neutral status line, never a guessed status
  that later corrects itself

#### Scenario: A locked-device wake keeps the membership
- **WHEN** the app is woken in the background before the phone has been unlocked since restart
- **THEN** the membership is kept, nothing is reset, and the work is done after the next unlock

### Requirement: The app keeps its place across backgrounding

Returning to the app from the background SHALL show the same screen the member left, with any open
surface and any half-typed text intact, and SHALL never present a blank or corrupted screen. An event that
finished meanwhile does not change that: the app never leaves an event on its own (capability
`event-lifetime`).

#### Scenario: Returning to an open settings surface
- **WHEN** the member opens settings, switches to another app, and comes back later
- **THEN** the settings surface is still open as they left it

#### Scenario: Returning after hours in the background
- **WHEN** the app was woken in the background several times and the member opens it hours later
- **THEN** it renders normally, never blank

#### Scenario: Returning after the event finished
- **WHEN** the member left the joined screen open, the event finished while the app was in the background,
  and the member returns and opens another event's invite
- **THEN** no switch question is asked and the new event's join screen is shown, as "Opening another event's
  invite asks before switching" decides (capability `invite-link`); the member joins only when they confirm

### Requirement: Background wakes do their work without showing anything

The app SHALL do the sharing and receiving work of a background wake — a silent notification, a
scheduled task or a finished transfer — exactly as it would in the foreground, without presenting any
screen.

#### Scenario: A silent notification wakes the app
- **WHEN** another member adds photos and iOS wakes the app in the background
- **THEN** the new photos are fetched and imported, and no screen is shown

### Requirement: The app menu is one tap away on every screen
Every screen SHALL show a menu button beside the app's name that opens the app menu, except while the event settings
are open and while a join or a create is in progress — there the menu SHALL NOT be offered: during a join or a
create the button SHALL NOT be shown, and while the settings are open over the joined screen, tapping that screen
closes the settings instead. The menu SHALL hold, first and set apart from the rest, the switch whether photos may
use mobile data with a note saying what it currently means (capability `mobile-data`); then, set apart, "Report a
problem" (capability `privacy-security`); then links to SnapSync's website and its Privacy Policy (capability
`web-site`); and, last, the app's version and build number, which is shown and not tappable. Following a link SHALL
open the page in the browser, leaving the app where it was. Flipping the switch SHALL leave the menu open. Closing
the menu — by tapping outside it, its close button, swiping it away or going back — SHALL leave the screen exactly
as it was before the menu opened, apart from what the switch changed.

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

### Requirement: Taps keep working and never fire twice

After any action fails, every later tap SHALL still work. An action that is already running SHALL NOT
run again from a second tap, and its result SHALL NOT appear on a surface the member has since left.

#### Scenario: A failure does not freeze the app
- **WHEN** one action fails and the member then taps another
- **THEN** the second action runs

#### Scenario: Double tap
- **WHEN** the member taps an action twice before the screen reacts
- **THEN** it runs once

### Requirement: Failures are told calmly, never as a red field

A failure after something the member submitted SHALL be stated as a message above the action, and a
problem the member did not cause — an invalid invite, an unreachable or missing event — SHALL be a
neutral notice. The app SHALL NOT mark a field the member typed in as erroneous for a failure that came
from elsewhere.

#### Scenario: A submission is refused
- **WHEN** the member creates an event and the request fails
- **THEN** the failure appears as a message above the Create action, and the name field is not reddened

### Requirement: A failing service is told as what it is

When SnapSync's service fails while the device has a usable network, the app SHALL tell the member which of three
conditions applies, each in its own words, wherever that screen tells a failure:

- **SnapSync cannot be reached**: no answer came back, or the answer was not one the app understands, such as a
  page a Wi-Fi network or a proxy put in its place;
- **SnapSync is having trouble**: the service answered that it failed;
- **SnapSync is being updated and is back shortly**: the service answered that it is briefly unavailable for an
  update.

Only *cannot be reached* SHALL suggest that the member check their connection. None of the three SHALL be shown as
the device having no network (capability `sync-status`), as this phone being refused (capability
`privacy-security`), or by marking anything the member typed as wrong. An answer the app does not understand SHALL
count as no answer: whatever the member or the app asked for SHALL NOT be treated as done, and SHALL be tried
again as after any other failure.

#### Scenario: No answer is told as unreachable
- **WHEN** the device has a network and the service does not answer
- **THEN** the app says SnapSync cannot be reached

#### Scenario: A failing service is told as trouble
- **WHEN** the service answers that it failed
- **THEN** the app says SnapSync is having trouble, and does not ask the member to check their connection

#### Scenario: An update is told as brief
- **WHEN** the service answers that it is being updated
- **THEN** the app says SnapSync is being updated and is back shortly, and does not ask the member to check their
  connection

#### Scenario: A Wi-Fi login page is not an answer
- **WHEN** a Wi-Fi network answers in the service's place with a login page
- **THEN** the app says SnapSync cannot be reached, and nothing the member asked for counts as done

### Requirement: Explanations always match the current choices

Every line that explains the consequence of a choice SHALL reflect the choices currently made, so the
screen never promises an album, a feed or a date range the membership will not produce.

#### Scenario: Changing a choice updates its explanation
- **WHEN** the member changes the date range they share
- **THEN** the line stating what will be shared updates to the new range at once

### Requirement: Picking a date range is guided and only offers what is allowed

Picking a date range SHALL happen in one dialog holding the dates and both times, committed by one
confirmation. The first tap on a day SHALL set the start, the second the end (the same day twice meaning
a single day), and a third SHALL start over; changing the days SHALL keep the chosen times. Where a
range is bounded — an event's range, and the range a member shares (at most 30 days, overlapping the event's
dates, capability `join-event`) — days and times that would break the bound SHALL be unselectable and a
picked value SHALL be kept within it. When picking the range a member shares, the event's own days SHALL stay
marked in the calendar. An end before the start SHALL be unreachable. Cancelling SHALL leave the previous
choice untouched. The dialog SHALL hold only the calendar and the times: the quick choices for what a member
shares are on the screen beside it (capability `join-event`).

#### Scenario: A shared range around the event
- **WHEN** the member picks a custom range for what they share
- **THEN** the event's days are marked, only days that keep the range at most 30 days long and overlapping the
  event can be picked, and the confirmed range has its end after its start

#### Scenario: Third tap starts over
- **WHEN** a start and end day are picked and the member taps a third day
- **THEN** that day becomes the new start

#### Scenario: Cancel keeps the old choice
- **WHEN** the member opens the custom picker and cancels
- **THEN** the previous choice is unchanged

### Requirement: Text entry sheets stay usable while typing

A sheet that asks for a line of text SHALL keep its field and both actions visible while the keyboard
is shown, SHALL keep confirm disabled while the text is empty or unchanged from what it opened with,
SHALL trim surrounding spaces, and SHALL show a refusal as a message rather than on the field. While its
action is running it SHALL stay open, show that it is working, and refuse both a second confirm and
dismissal.

#### Scenario: The keyboard does not cover confirm
- **WHEN** the member types into a text sheet with the keyboard up
- **THEN** the confirm and cancel actions remain visible and tappable

#### Scenario: Unchanged text cannot be submitted
- **WHEN** a sheet opens with an existing value and the member has not changed it
- **THEN** confirm is disabled

#### Scenario: A running sheet cannot be dismissed
- **WHEN** the member has confirmed and the action is still running
- **THEN** the sheet stays open showing progress, and neither cancel, swiping it away, tapping outside it nor
  Android's back gesture closes it or moves it off the screen

### Requirement: Controls are accessible and honour reduced motion

Every interactive row SHALL be a single control announcing its role and on/off or checked state; an
unavailable control SHALL stay present and be announced as unavailable rather than disappearing. With
iOS's reduce-motion setting on, the status arrows SHALL NOT pulse — a transferring arrow still shows as
transferring, without moving.

#### Scenario: Reduce motion
- **WHEN** reduce motion is on and a transfer is running
- **THEN** the arrow is shown as transferring without moving

#### Scenario: A dimmed choice
- **WHEN** VoiceOver reaches a choice that is currently unavailable
- **THEN** it is announced as one control, unavailable
