# Spec Delta

## MODIFIED Requirements

### Requirement: Any member renames the event for everyone until its photos are deleted
Until the event's photos are deleted (capability `event-lifetime`), every member SHALL be able to rename the event from
the joined screen. The rename dialog SHALL open with the current name, accept at most 100 characters, and
disable confirming while the name is empty or unchanged; surrounding whitespace SHALL be dropped. On
success the dialog SHALL close and the new name SHALL show at once; every other member SHALL see it the
next time they open the app, and the event's web page shows it too (capability `event-site`). A rename
SHALL change nothing but the name — not the invite, the dates, or anyone's settings; an invite shared before it keeps
opening the same event. Once the event's photos have been deleted, rename SHALL NOT be offered, and a rename confirmed
just before the deletion SHALL fail, saying the event is over (as "A failed rename keeps the dialog and never ends the
membership" requires). While the phone cannot read an encrypted event's key — after it lost the key (capability
`sync-status`) or while the phone is locked since it was started — rename SHALL NOT be offered, since only the key can
keep the new name encrypted (capability `invite-link`); it SHALL return as soon as the key can be read again.

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

#### Scenario: A phone that lost the event's key cannot rename
- **WHEN** a member of an encrypted event whose phone lost the event's key opens the joined screen
- **THEN** no rename action is offered, and it returns once they open the event's whole invite
