## MODIFIED Requirements

### Requirement: The joined screen is whole in every state

Once an event is joined, the app SHALL show one joined screen carrying the event's name, its invite
(capability `manage-membership`), a single status line, and the membership actions — settings, share,
leave and rename. Every one of those SHALL be present whatever the status line says, including without
photo access, before the event starts, and while the device cannot be verified. Once the event has closed
(capability `event-lifetime`), the joined screen SHALL carry only the event's name, the status line and
Leave.

#### Scenario: No access still shows everything
- **WHEN** a joined member has no photo access
- **THEN** the event name, the invite, settings, share, leave and rename are all present, with the
  status line asking for access

#### Scenario: Before the start everything is available
- **WHEN** a joined event has not started yet
- **THEN** the member can already share the invite, change settings, rename and leave

#### Scenario: A closed event keeps only Leave
- **WHEN** a member opens the joined screen of an event that has closed and is still receiving its last
  photos
- **THEN** the event name, the status line and Leave are shown, and no invite, share, settings or rename

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen SHALL show "Event ended" on its own line
above the status line — never merged into the status text — and SHALL gain it within a minute of the end
passing while the app is open. While the event has not closed and the status line reads "In sync", that
line SHALL also say how many of the event's current members it is still waiting for to finish sharing
(capability `event-lifetime`). The marker SHALL change nothing else: arrows, status and syncing continue
exactly as before (the end bounds only which photos may be shared — capability `event-lifetime`).

#### Scenario: Ended and still syncing
- **WHEN** the event's range has ended and uploads are still outstanding
- **THEN** "Event ended" appears above "Synchronization pending…" and the uploads continue

#### Scenario: The marker appears while open
- **WHEN** the app is open and the event's end passes
- **THEN** within a minute "Event ended" appears

#### Scenario: Waiting for the others
- **WHEN** the range has ended, this member is in sync, and two of the event's five members have not yet
  finished sharing
- **THEN** the ended line says the event is waiting for 2 of 5 members

### Requirement: The app keeps its place across backgrounding

Returning to the app from the background SHALL show the same screen the member left, with any open
surface and any half-typed text intact, and SHALL never present a blank or corrupted screen — unless the
membership ended on its own meanwhile (capability `manage-membership`), in which case it SHALL show the
create screen.

#### Scenario: Returning to an open settings surface
- **WHEN** the member opens settings, switches to another app, and comes back later
- **THEN** the settings surface is still open as they left it

#### Scenario: Returning after hours in the background
- **WHEN** the app was woken in the background several times and the member opens it hours later
- **THEN** it renders normally, never blank

#### Scenario: Returning after the event finished
- **WHEN** the member left the joined screen open, the event finished and the app left it in the
  background, and the member returns
- **THEN** the create screen is shown
