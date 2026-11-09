# Spec Delta

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

The event's name, dates, status and members SHALL be shown even when the browser runs no script. An encrypted
event's name, which SnapSync's service cannot read (capability `invite-link`), SHALL be shown without a script only
as the name its invite carries for previews, and not at all when the invite carries none. Once the whole invite has
opened the event in the browser, the page SHALL show the name the members gave the event in its place, even where
the invite's preview name is missing, stale or altered. The page SHALL work in any modern browser on any platform,
with no install, no account and no sign-in.

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

#### Scenario: An encrypted event without scripts and without a preview name
- **WHEN** a visitor opens an encrypted event's whole invite that carries no preview name, in a browser that runs no
  script
- **THEN** the page shows the event's dates, status and members, and names no event

#### Scenario: The members' name replaces a stale preview name
- **WHEN** an encrypted event "Ana's 3oth" was renamed to "Ana's 30th", and a visitor opens an invite shared before the
  rename, whole, in a browser that runs scripts
- **THEN** the page names the event "Ana's 30th"

### Requirement: A shared invite previews its event
When an invite of the path form is shared in a messenger or anywhere else that shows a link preview, the
preview SHALL name the event and SHALL show its dates and how many members it has, or once it has ended,
how many have finished sharing. An encrypted event's preview SHALL name the event as its invite's preview name
(capability `invite-link`), and SHALL name no event when the invite carries none. A preview SHALL NEVER show any of
the event's photos. The preview of an invite whose event never existed, or whose photos were deleted because it
finished or reached the end of its lifetime, SHALL say the link is invalid or expired and SHALL NOT name any event,
whatever name the invite carries. An invite of the fragment form SHALL preview as SnapSync's event page, naming no
event.

#### Scenario: Sharing an invite in a chat
- **WHEN** a member pastes an event's path-form invite into a messenger
- **THEN** the link preview shows the event's name, its dates and how many members it has

#### Scenario: Sharing an encrypted event's invite in a chat
- **WHEN** a member of the encrypted event "Ana's 30th" shares its invite into a messenger
- **THEN** the link preview shows "Ana's 30th", its dates and how many members it has

#### Scenario: A preview shows no photo
- **WHEN** an invite of an event full of photos is previewed
- **THEN** the preview's image is SnapSync's icon, not a photo from the event

#### Scenario: An expired invite's preview
- **WHEN** the path-form invite of a finished event whose photos were deleted is pasted into a messenger
- **THEN** the preview says the link is invalid or expired, and names no event

#### Scenario: An expired encrypted invite's preview names nothing
- **WHEN** the invite of a deleted encrypted event, carrying its name for previews, is pasted into a messenger
- **THEN** the preview says the link is invalid or expired, and names no event

### Requirement: An encrypted event's photos open only with the whole invite
An encrypted event's page opened with its whole invite SHALL offer the event's photos exactly as a plain event's page
does, opened in the visitor's browser (capability `invite-link`), and the zip SHALL be named after the name the
members gave the event. Opened without the key, or with another, the page SHALL still show the event's dates, status
and members, SHALL name the event only as its invite's preview name, if it carries one, SHALL say that only the whole
invite opens its photos, SHALL still offer "Get SnapSync", and SHALL offer no download.

#### Scenario: The whole invite downloads the photos
- **WHEN** a visitor opens an encrypted event's whole invite in a browser and downloads its photos
- **THEN** the zip holds every shared photo, each one viewable as it was taken, and is named after the event

#### Scenario: An invite without its key
- **WHEN** a visitor opens an encrypted event's invite whose key was cut off
- **THEN** the page names the event as the invite's preview name, shows its dates, status and members, says the whole
  invite is needed for the photos, and offers no download

#### Scenario: An invite without its key or a preview name
- **WHEN** a visitor opens an encrypted event's invite that carries neither its key nor a preview name
- **THEN** the page shows its dates, status and members, names no event, says the whole invite is needed for the
  photos, and offers no download
