## MODIFIED Requirements

### Requirement: The missing-access line is the one line the member can act on

When photo access is missing, the status line SHALL be a tappable attention line: tapping it SHALL
raise the system's access dialog if access was never decided, and SHALL open the app's page in the phone's
Settings if it was refused (capability `photo-access`). It SHALL be the only tappable status line.

#### Scenario: Never asked
- **WHEN** access was never decided and the member taps the line
- **THEN** the system's photo-access dialog appears

#### Scenario: Previously refused
- **WHEN** access was refused and the member taps the line
- **THEN** the app's page in the phone's Settings opens
