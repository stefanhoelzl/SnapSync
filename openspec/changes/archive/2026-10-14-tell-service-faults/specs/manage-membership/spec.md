## MODIFIED Requirements

### Requirement: A failed rename keeps the dialog and never ends the membership
When a rename fails, the dialog SHALL stay open with the typed name, and show the failure as a message
above confirm — telling a rejected name apart from a connection problem, and both apart from a failing service,
which it SHALL tell as the condition that applies (capability `app-experience`, "A failing service is told as what
it is") — never by marking the name field as wrong. The app SHALL NOT try the rename again until the member
confirms it again. No failed rename SHALL change the member's membership, and in particular a rename
that finds the event missing SHALL NOT end it. When the service answers a rename that the event's photos were
deleted, the dialog SHALL say that the event is over, and the joined screen SHALL show the event as over
(capability `event-lifetime`, "The app never leaves an event on its own"), with the member still in it.

#### Scenario: Offline rename
- **WHEN** a member confirms a rename while offline
- **THEN** the dialog stays open with their text and says the event could not be renamed and to check the connection

#### Scenario: A rejected name
- **WHEN** the server refuses the new name
- **THEN** the dialog stays open and says the name was not accepted

#### Scenario: A rename the service fails says which way
- **WHEN** a member confirms a rename and the service answers that it failed, or that it is being updated
- **THEN** the dialog stays open with their text and says SnapSync is having trouble, or is being updated and is
  back shortly, without asking them to check their connection

#### Scenario: A rename that meets a missing event keeps the member joined
- **WHEN** a rename fails because the server no longer finds the event
- **THEN** the member stays joined, with the generic failure message and nothing torn down

#### Scenario: A rename that meets a deleted event says it is over
- **WHEN** a member confirms a rename and the service answers that the event's photos were deleted
- **THEN** the dialog stays open and says the event is over, the joined screen shows the event as over, and the
  member stays in it

### Requirement: Any member renames the event for everyone until its photos are deleted
Until the event's photos are deleted (capability `event-lifetime`), every member SHALL be able to rename the event from
the joined screen. The rename dialog SHALL open with the current name, accept at most 100 characters, and
disable confirming while the name is empty or unchanged; surrounding whitespace SHALL be dropped. On
success the dialog SHALL close and the new name SHALL show at once; every other member SHALL see it the
next time they open the app, and the event's web page shows it too (capability `event-site`). A rename
SHALL change nothing but the name — not the invite, the dates, or anyone's settings. Once the event's
photos have been deleted, rename SHALL NOT be offered, and a rename confirmed just before the deletion SHALL
fail, saying the event is over (as "A failed rename keeps the dialog and never ends the membership" requires).

#### Scenario: A guest fixes a typo in the name
- **WHEN** a member who did not create the event renames it from "Ana's 3oth" to "Ana's 30th"
- **THEN** the dialog closes, their joined screen shows "Ana's 30th", and another member sees the new name the next time they open SnapSync

#### Scenario: An unchanged or empty name cannot be submitted
- **WHEN** the rename field holds the current name, or only spaces
- **THEN** the confirm action is disabled

#### Scenario: A rename leaves everything else alone
- **WHEN** the event is renamed
- **THEN** its QR code, its dates, and every member's sharing and receiving settings are unchanged

#### Scenario: Cancelling a rename changes nothing
- **WHEN** the member edits the name and cancels
- **THEN** the event keeps its name

#### Scenario: An ended event can still be renamed
- **WHEN** a member renames an event a week after its range ended, before its photos were deleted
- **THEN** the event is renamed for everyone

#### Scenario: A deleted event cannot be renamed
- **WHEN** a member opens the joined screen of an event whose photos were deleted
- **THEN** no rename action is offered
