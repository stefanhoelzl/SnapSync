## MODIFIED Requirements

### Requirement: An invite link without the app opens the event's page
Opening an event's invite link, in either form (capability `invite-link`), in a browser where SnapSync does
not claim it SHALL show a page naming the event and how many photos are ready to download. The page SHALL
also show:
- the event's dates as the host chose them, in the host's own calendar, wherever the visitor is (an event
  created before the event kept its host's calendar shows its dates in Coordinated Universal Time);
- whether the event has not started yet, is happening now, or has ended;
- how many members it has;
- once it has ended, how many of them have finished sharing their photos, and the date until which its
  photos stay available at the latest (capability `event-lifetime`).

The event's name, dates, status and members SHALL be shown even when the browser runs no script. The page
SHALL work in any modern browser on any platform, with no install, no account and no sign-in.

#### Scenario: A guest on Android opens the invite
- **WHEN** a guest opens a valid invite link on an Android phone
- **THEN** a page shows the event's name, its dates, how many members it has, and the number of photos available

#### Scenario: A guest on a computer opens the invite
- **WHEN** a guest opens a valid invite link in a desktop browser
- **THEN** the same event page is shown

#### Scenario: Both forms open the same page
- **WHEN** a visitor opens an event's invite of the fragment form, and another opens the same event's invite of the path form
- **THEN** both see the same page for that event

#### Scenario: The dates are the host's
- **WHEN** a host in Berlin creates an event from Saturday 4 October to Sunday 5 October, and a visitor in New York opens its invite
- **THEN** the page shows Saturday 4 October to Sunday 5 October

#### Scenario: An event that has not started
- **WHEN** a visitor opens the invite of an event whose start is still ahead
- **THEN** the page says when the event starts

#### Scenario: An ended event still settling
- **WHEN** a visitor opens the invite of an event that has ended, where 4 of its 6 members have finished sharing
- **THEN** the page says the event has ended, that 4 of 6 members have finished sharing, and until when the photos stay available

#### Scenario: A browser without scripts
- **WHEN** a visitor opens a valid invite of the path form in a browser that runs no script
- **THEN** the page still names the event and shows its dates, status and members

### Requirement: An encrypted event's photos open only with the whole invite
An encrypted event's page opened with its whole invite SHALL offer the event's photos exactly as a plain event's page
does, opened in the visitor's browser (capability `invite-link`). Opened without the key, or with another, the
page SHALL still name the event and show its dates, status and members, SHALL say that only the whole invite opens
its photos, SHALL still offer "Get SnapSync", and SHALL offer no download.

#### Scenario: The whole invite downloads the photos
- **WHEN** a visitor opens an encrypted event's whole invite in a browser and downloads its photos
- **THEN** the zip holds every shared photo, each one viewable as it was taken

#### Scenario: An invite without its key
- **WHEN** a visitor opens an encrypted event's invite whose key was cut off
- **THEN** the page names the event and shows its dates, status and members, says the whole invite is needed for
  the photos, and offers no download
