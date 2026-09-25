# photo-access Specification

## Purpose

Serves every member — host or guest — at the one decision SnapSync ever asks of them: whether the app
may see their photos. Once access is on and an event is joined, sharing runs by itself with no upload
button and no per-photo consent. Full and limited access are both working answers: under limited
access the photos the member hand-picked in iOS are exactly what they share, which for a guest at
someone else's event is often the more natural choice. Missing access never hides the event, never
nags, and is only ever requested because the member asked for it; the app offers its own routes to
widen access and picks up any change made in iOS Settings.
Decision record: changes/archive/2026-07-20-accept-limited-photo-access

## Requirements

### Requirement: iOS's access dialog is raised only by a deliberate tap

The app SHALL raise iOS's photo-access dialog only in response to a deliberate user action — tapping
the joined screen's access line (capability `sync-status`) or continuing past the join explainer
(capability `join-event`) — and never merely because access has not been decided yet. Once iOS has
recorded an answer, the same tap SHALL take the member to the app's page in iOS Settings instead.

#### Scenario: Opening the app does not prompt
- **WHEN** a joined member whose access has never been decided opens the app
- **THEN** no iOS access dialog appears until they tap the access line

#### Scenario: A refused member is taken to Settings
- **WHEN** a member who previously refused access taps the access line
- **THEN** the app's page in iOS Settings opens and no dialog is raised

### Requirement: Missing access never hides or blocks the event

A member without photo access SHALL still see the full joined screen — the event, its invite, and
every membership action — with a single line inviting them to turn access on. Without access nothing
of the member's SHALL be shared, and the event SHALL remain joined and unchanged until access is
granted.

#### Scenario: Denied access keeps the event usable
- **WHEN** a joined member has refused photo access
- **THEN** they can still show the invite, share the link, open settings, rename and leave, and the
  status line asks them to turn access on

#### Scenario: Granting access later starts sharing
- **WHEN** a joined member without access grants it
- **THEN** their photos in the event's range start sharing without any further step

### Requirement: Limited access is a working membership whose scope is the selection

Under limited access the photos the member selected in iOS — and only those — SHALL be the photos
considered for sharing, filtered exactly as a full library would be (capability `photo-sharing`).
Nothing outside the selection SHALL ever be uploaded, by the app or in the background. "In sync"
SHALL mean every selected, in-range photo is shared and everything received has arrived; limited
access SHALL NOT be shown as a problem.

#### Scenario: Selected photos share through the ordinary rules
- **WHEN** a limited-access member has selected photos, some taken inside the event's range and some
  before it
- **THEN** only the selected photos inside the range are shared

#### Scenario: A photo outside the selection never leaves the device
- **WHEN** a limited-access member takes a new photo that is not in their selection
- **THEN** it is not uploaded, in the foreground or the background, until they add it to the selection

#### Scenario: In sync over the selection
- **WHEN** every selected in-range photo is shared and received photos have arrived
- **THEN** the status line reads "In sync"

### Requirement: Receive-only with limited access is never nagged

A limited-access member who selects nothing SHALL still receive the event's photos into their library
(and the event album, if chosen — capability `event-album`), and the app SHALL NOT treat that state as
an error or prompt them to select photos.

#### Scenario: An empty selection still receives
- **WHEN** a limited-access member with nothing selected is joined with receiving on
- **THEN** other members' photos arrive in their library, nothing is uploaded, and no prompt asks them
  to pick photos

### Requirement: The app offers its own routes to widen limited access

While access is limited, the joined screen SHALL offer two calm, always-present choices outside the
status line: "Choose more photos", which opens iOS's picker to add to the selection, and "Allow full
access", which opens the app's page in iOS Settings. Neither SHALL be an attention state, and neither
SHALL be offered under full access or no access. Photos added to the selection — through the picker or
in iOS Settings — SHALL be shared like any other selected photo.

#### Scenario: Choosing more photos shares them
- **WHEN** a limited-access member taps "Choose more photos" and adds in-range photos
- **THEN** iOS's picker appears and the added photos are shared

#### Scenario: Allow full access goes straight to Settings
- **WHEN** a limited-access member taps "Allow full access"
- **THEN** the app's page in iOS Settings opens, with no in-app dialog in between

#### Scenario: Full access shows neither choice
- **WHEN** the member has full access
- **THEN** neither "Choose more photos" nor "Allow full access" is shown

### Requirement: The app never raises iOS's limited-library prompt itself

The app SHALL NOT cause iOS's automatic "Select More Photos / Keep Current Selection" prompt to
appear — neither from the app nor from its background uploads — and SHALL instead own the route to
widen the selection through "Choose more photos".

#### Scenario: Normal use under limited access
- **WHEN** a limited-access member uses the app and it shares and receives photos in the foreground and
  background
- **THEN** SnapSync itself never triggers iOS's limited-library prompt

### Requirement: Access changes made in iOS Settings are picked up on return

A change to photo access made outside the app SHALL take effect the next time the app comes to the
foreground, without any action inside the app.

#### Scenario: Revoking access in Settings
- **WHEN** a member with access turns it off in iOS Settings and returns to the app
- **THEN** the status line asks them to turn access on

#### Scenario: Granting access in Settings
- **WHEN** a member without access turns it on in iOS Settings and returns to the app
- **THEN** the access line disappears and sharing begins

### Requirement: Switching between full and limited access never re-uploads

Moving from full to limited access SHALL narrow what is shared to the selection from then on: selected
photos already shared SHALL stay shared without being uploaded again, and photos outside the selection
SHALL be withdrawn like any de-selected photo (capability `photo-sharing`). Moving from limited to full
access SHALL share only the in-range photos not yet shared, re-uploading nothing.

#### Scenario: Widening to full access
- **WHEN** a member who shared photos under limited access switches to full access
- **THEN** only newly in-scope photos from the event's range upload, and nothing already shared is sent
  again

#### Scenario: Widening right after changing the selection
- **WHEN** a limited-access member changes their selection and switches to full access before the app has
  finished looking at the changed selection
- **THEN** no shared photo is withdrawn because of the old selection, and nothing is uploaded again

#### Scenario: Narrowing to a selection
- **WHEN** a member who shared photos under full access switches to limited access with a selection
  that excludes some of them
- **THEN** the excluded photos leave the event for the other members, the selected ones stay without
  being uploaded again, and the status reflects the selection

### Requirement: A selection the app has not yet looked at withdraws nothing

The app SHALL NOT withdraw any photo from the event, and SHALL NOT report the member as in sync, until
it has read the member's limited selection after launch or after access became limited.

#### Scenario: Reopening the app under limited access
- **WHEN** a limited-access member reopens the app and their selection has not been read yet
- **THEN** none of their shared photos disappears from the event, and the status line does not read
  "In sync" until the selection has been read
