# desktop-app-shell Delta Specification

## MODIFIED Requirements

### Requirement: Desktop application window

The system SHALL provide a JVM desktop application built with Compose Desktop that, when launched, opens a single top-level application window titled "SnapSync".

#### Scenario: Launching the app opens the window
- **WHEN** the desktop application is launched via `./gradlew :app:desktop:run`
- **THEN** a single application window opens with the title "SnapSync"

#### Scenario: Window hosts the test harness
- **WHEN** the application window is open
- **THEN** the window presents the dual-pane desktop test harness (phone-framed status screen + control panel) as its content
