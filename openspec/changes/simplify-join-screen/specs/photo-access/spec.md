## MODIFIED Requirements

### Requirement: iOS's access dialog is raised only by a deliberate tap

The app SHALL raise iOS's photo-access dialog only in response to a deliberate user action — tapping
the joined screen's access line (capability `sync-status`) or confirming a join whose confirm action says
it also allows photo access (capability `join-event`) — and never merely because access has not been
decided yet. Once iOS has recorded an answer, the same tap SHALL take the member to the app's page in iOS
Settings instead.

#### Scenario: Opening the app does not prompt
- **WHEN** a joined member whose access has never been decided opens the app
- **THEN** no iOS access dialog appears until they tap the access line

#### Scenario: A refused member is taken to Settings
- **WHEN** a member who previously refused access taps the access line
- **THEN** the app's page in iOS Settings opens and no dialog is raised
