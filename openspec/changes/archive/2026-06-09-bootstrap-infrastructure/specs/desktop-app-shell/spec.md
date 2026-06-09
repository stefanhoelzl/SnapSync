## ADDED Requirements

### Requirement: Desktop application window

The system SHALL provide a JVM desktop application built with Compose Desktop that, when launched, opens a single top-level application window titled "SnapSync".

#### Scenario: Launching the app opens the window
- **WHEN** the desktop application is launched via `./gradlew :app:desktop:run`
- **THEN** a single application window opens with the title "SnapSync"

#### Scenario: Window is empty in this change
- **WHEN** the application window is open
- **THEN** the window presents an empty Compose surface with no content, reserved as the future host for the application UI

### Requirement: Application lifecycle

The system SHALL terminate the application process when the user closes the application window, so the launched process does not linger.

#### Scenario: Closing the window exits the app
- **WHEN** the user closes the application window
- **THEN** the application process exits cleanly

### Requirement: Buildable on the verified toolchain

The project SHALL build with Gradle on the verified toolchain (Gradle 9.5.1, JDK 25 via an auto-provisioned toolchain, Kotlin 2.4.0, Compose Multiplatform 1.11.1), and the application SHALL run on JDK 25 rather than the Gradle launcher JVM.

#### Scenario: Project builds green
- **WHEN** `./gradlew build` is run
- **THEN** the build completes successfully with no errors

#### Scenario: Run task uses the JDK 25 toolchain
- **WHEN** the application is run via the Compose Desktop `run` task
- **THEN** it executes on the JDK 25 toolchain (not the Gradle launcher JVM) and does not fail with `UnsupportedClassVersionError`
