# desktop app shell Specification

## Purpose

The launchable JVM/Compose Desktop shells — the build targets and entry points that open a harness
window. The desktop app is **test equipment, not a product**: there are two shells, one per harness, and
this capability owns only the windows. What each window contains belongs to the harness that owns it
(`desktop-test-harness` for the forge, `full-stack-harness` for the world).

## Requirements

### Requirement: Two desktop shells, one per harness

The system SHALL provide two JVM Compose Desktop entry points — both in the one `:app:desktop`
module since migration step 10 — each opening a single top-level window:

- `./gradlew :app:desktop:runForge` — the **forge** harness, titled `SnapSync`, hosting the
  dual-pane forge (phone-framed status screen + control panel; capability `desktop-test-harness`).
- `./gradlew :app:desktop:run` — the **full-stack world** harness, titled `SnapSync — full-stack
  world`, hosting the phone-framed status screen whose counts emerge from the real world + the
  world inspector (capability `full-stack-harness`).

The titles SHALL differ, because both windows show the same phone frame and only the title
distinguishes a forged pane from one whose counts are real — mistaking them is mistaking a drawing
for a measurement.

`:app:desktop` SHALL also hold the shared pane library (`PhoneFrame`, `StatusPane`, the
`StatusContainerHost` wiring) both entry points mount — so the two shells cannot drift in how they
mount the real screen. (The Compose Desktop plugin models one `application {}` main class per
module; the world keeps it, and the forge entry is a plain `JavaExec` on the same toolchain and
JVM arguments.)

#### Scenario: The world shell opens
- **WHEN** launched via `./gradlew :app:desktop:run`
- **THEN** a single window opens titled `SnapSync — full-stack world`, presenting the status screen and the world inspector

#### Scenario: The forge shell opens
- **WHEN** launched via `./gradlew :app:desktop:runForge`
- **THEN** a single window opens titled `SnapSync`, presenting the status screen and the forge control panel

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
