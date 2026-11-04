## MODIFIED Requirements

### Requirement: An ended event is marked without stopping sync

After the end of the event's date range, the joined screen's dates SHALL say the event has ended — never
merged into the status line's text — and SHALL say so within a minute of the end passing while the app is
open. While the status line reads "Up to date" or "Waiting for photos…", the screen SHALL also say, with the
photo counts, how many of the members still in the event it is waiting for to finish sharing, without the event's total (capability
`event-lifetime`); once every member still in the event has finished sharing — settled what they share, and
every photo they share has reached the event — and this member is up to date, it SHALL instead say that everyone
has shared their photos, whether or not the others have received everything yet. A guest joining late, or a member sharing more photos,
SHALL turn that back into the waiting line. Ending SHALL change nothing else: arrows, status, counts and
syncing continue exactly as before (the end bounds only which photos may be shared — capability
`event-lifetime`).

#### Scenario: Ended and still syncing
- **WHEN** the event's range has ended and uploads are still outstanding
- **THEN** the dates say the event has ended, the status line reads "Photos queued…" and the
  uploads continue

#### Scenario: The marker appears while open
- **WHEN** the app is open and the event's end passes
- **THEN** within a minute the dates say the event has ended

#### Scenario: Waiting for the others
- **WHEN** the range has ended, this member is up to date, and two of the event's five members have not yet
  finished sharing
- **THEN** the screen says the event is waiting for 2 members

#### Scenario: Waiting for the others' photos after the end
- **WHEN** the range has ended, this member has shared everything and holds everything that has arrived, and one
  of the event's members still has declared photos uploading
- **THEN** the status line reads "Waiting for photos…" and the screen says the event is waiting for 1 member

#### Scenario: Everyone has shared
- **WHEN** the range has ended, this member is up to date, and every member still in the event has finished
  sharing, though one of them has not yet received every photo
- **THEN** the screen says everyone has shared their photos

#### Scenario: A late guest turns it back
- **WHEN** the screen says everyone has shared and a guest joins the event and starts sharing
- **THEN** the screen says the event is waiting for 1 member again

