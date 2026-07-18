# desktop-app-shell — delta for collapse-harness-onto-shared-composition

## MODIFIED Requirements

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
