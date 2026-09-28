## MODIFIED Requirements

### Requirement: The shared capture range always has a lower bound inside the event window
With sharing on, the join screen SHALL let the user choose the range of capture dates they share,
defaulting to the whole event window. The choice SHALL be one of: the whole event; from now until the
event's end; or a custom range, picked on a calendar with a time for its start and its end. From now
SHALL be offered only while the event's window is running. A custom range SHALL be limited to the event's
window, and the joined range SHALL always lie within the event's start and end, whichever way the range
reached the app. A membership SHALL never exist without a lower bound: if the app cannot tell a
membership's range, it SHALL share nothing rather than the whole library.

#### Scenario: The default is the whole event window
- **WHEN** the join screen loads an event running from Friday 18:00 to Sunday 23:00
- **THEN** the shared range reads from Friday 18:00 until Sunday 23:00, as the whole event

#### Scenario: From now shares from this moment to the event's end
- **WHEN** the event is running and the user chooses from now
- **THEN** the shared range reads from the current time until the event's end

#### Scenario: Now is unavailable outside the window
- **WHEN** the event has not started yet, or has already ended
- **THEN** from now cannot be chosen and the default remains the whole event window

#### Scenario: A custom time cannot leave the window
- **WHEN** the user tries to pick a custom range starting before the event's start or ending after its end
- **THEN** the calendar holds the choice to the event's start or end

#### Scenario: A guest's older camera roll is never shared
- **WHEN** a guest with years of photos joins an event that started yesterday
- **THEN** only photos taken since the event's start, within its range, are shared — never their older photos

#### Scenario: An unknown range shares nothing
- **WHEN** the app cannot read a membership's capture range
- **THEN** no photo is shared until the user joins again

#### Scenario: A choice survives a failed join
- **WHEN** the user picks a custom range, taps Join, the join fails, and they tap Retry
- **THEN** the retry joins with the range they picked, not a default

### Requirement: Photo access is explained before iOS ever asks
To a user who is in no event and has never been asked for photo access, the join screen SHALL state,
once it has loaded the event, that iOS will ask for photo access next, and SHALL offer on request an
explanation stating that photos they take are shared automatically, that the photo library is needed
both to share and to save others' photos, that choosing specific photos also works, and that only photos
in the range they chose are shared. Viewing the explanation SHALL raise nothing. For this user the
confirm action SHALL say that it also allows photo access, and tapping it SHALL raise iOS's photo access
dialog and join the event whatever they answer (without access, capability `photo-access`). iOS's dialog
SHALL NOT be raised before that tap. A user who has already granted, limited, or denied access SHALL see
neither the notice nor the changed confirm action, and no dialog is raised when they join (capability
`photo-access`).

#### Scenario: A first-time guest is told before iOS asks
- **WHEN** a guest who has never been asked for photo access opens an invite and the event loads
- **THEN** the join screen, with all its choices, says iOS will ask for photo access next, the confirm action says it also allows photo access, and no iOS dialog appears

#### Scenario: The explanation is available on request
- **WHEN** that guest asks for the explanation
- **THEN** it names what joining does with their photos, and closing it leaves them on the join screen with no dialog raised

#### Scenario: Confirming raises iOS's dialog and joins
- **WHEN** the guest taps the confirm action
- **THEN** iOS's photo access dialog appears and the device joins the event with the choices they made, whether they then allow, limit or refuse access

#### Scenario: Cancelling abandons the join without asking
- **WHEN** the guest taps Cancel
- **THEN** the device is in no event, the create screen is shown, and no iOS dialog was raised

#### Scenario: A user who already answered iOS joins without a dialog
- **WHEN** a user who previously denied or granted photo access opens an invite and joins
- **THEN** no notice about access is shown, the confirm action is the plain Join, and no dialog is raised

## REMOVED Requirements

### Requirement: The join screen states when the event's photos are deleted
**Reason**: The join screen is simplified to the decisions a guest makes; the deletion date is not one of them. The retention promise itself is unchanged (capability `event-lifetime`) — the app no longer recites it.
**Migration**: None. The event is still deleted 30 days after it starts; no screen states the date.
