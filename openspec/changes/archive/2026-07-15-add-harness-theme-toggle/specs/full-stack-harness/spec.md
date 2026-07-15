## ADDED Requirements

### Requirement: Phone-pane theme toggle

The world inspector SHALL provide a Light/Dark toggle that forces the **phone pane's** theme
deterministically, so the shipped dark skin can be reviewed against the real stack without a device.
The toggle SHALL drive the design system's test-only theme override (`LocalDarkThemeOverride`) around
the rendered `StatusScreen` only — via the shared `StatusPane`'s `darkThemeOverride` input — so the
phone pane renders the real `AppTheme` in the chosen theme. The toggle SHALL NOT depend on the host OS
setting, SHALL default to Light, and SHALL leave the world inspector's own raw-Material 3 chrome
unthemed by it. The toggle is a pure view control: it SHALL NOT mutate world state and SHALL survive a
preset rebuild (fresh world) unchanged.

#### Scenario: Toggle forces the phone pane dark
- **WHEN** the operator sets the theme toggle to Dark
- **THEN** the phone pane's status screen renders in the dark color scheme, independent of the host OS setting

#### Scenario: Toggle is independent of world state
- **WHEN** the operator applies a preset that rebuilds a fresh world
- **THEN** the chosen theme is retained and no world state is mutated by the toggle
