## ADDED Requirements

### Requirement: Photo access is explained before the system ever asks
To a user who is in no event and has never been asked for photo access, the join screen SHALL state,
once it has loaded the event, that the phone's system will ask for photo access next, and SHALL offer on
request an explanation stating that photos they take are shared automatically, that the photo library is
needed both to share and to save others' photos, that choosing specific photos also works where the phone
offers it, and that only photos in the range they chose are shared. Viewing the explanation SHALL raise
nothing. For this user the confirm action SHALL say that it also allows photo access, and tapping it SHALL
raise the system's photo access dialog and join the event whatever they answer (without access, capability
`photo-access`). The system's dialog SHALL NOT be raised before that tap. A user who has already granted,
limited, or denied access SHALL see neither the notice nor the changed confirm action, and no dialog is
raised when they join (capability `photo-access`).

#### Scenario: A first-time guest is told before the system asks
- **WHEN** a guest who has never been asked for photo access opens an invite and the event loads
- **THEN** the join screen, with all its choices, says the system will ask for photo access next, the confirm action says it also allows photo access, and no access dialog appears

#### Scenario: The explanation is available on request
- **WHEN** that guest asks for the explanation
- **THEN** it names what joining does with their photos, and closing it leaves them on the join screen with no dialog raised

#### Scenario: Confirming raises the system's dialog and joins
- **WHEN** the guest taps the confirm action
- **THEN** the system's photo access dialog appears and the device joins the event with the choices they made, whether they then allow, limit or refuse access

#### Scenario: Cancelling abandons the join without asking
- **WHEN** the guest taps Cancel
- **THEN** the device is in no event, the create screen is shown, and no access dialog was raised

#### Scenario: A user who already answered the system joins without a dialog
- **WHEN** a user who previously denied or granted photo access opens an invite and joins
- **THEN** no notice about access is shown, the confirm action is the plain Join, and no dialog is raised

## REMOVED Requirements

### Requirement: Photo access is explained before iOS ever asks
**Reason**: Replaced by "Photo access is explained before the system ever asks", the same outcome worded for iPhone and Android alike.
**Migration**: None; the behaviour on iPhone is unchanged.
