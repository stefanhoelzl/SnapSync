## ADDED Requirements

### Requirement: Phone-pane theme toggle

The control panel SHALL provide a Light/Dark toggle that forces the **phone pane's** theme
deterministically, so the shipped dark skin can be reviewed without a device. The toggle SHALL drive
the design system's test-only theme override (`LocalDarkThemeOverride`) around the rendered
`StatusScreen` only — via the shared `StatusPane`'s `darkThemeOverride` input — so the phone pane
renders the real `AppTheme` in the chosen theme. The toggle SHALL NOT depend on the host OS setting
(`isSystemInDarkTheme()`), which is unreliable on the desktop target. The toggle SHALL default to
Light, matching the harness's current appearance, and SHALL leave the control panel's own raw-Material 3
chrome unthemed by it.

#### Scenario: Toggle forces the phone pane dark
- **WHEN** the operator sets the theme toggle to Dark
- **THEN** the phone pane's status screen renders in the dark color scheme, independent of the host OS setting

#### Scenario: Toggle forces the phone pane light
- **WHEN** the operator sets the theme toggle to Light
- **THEN** the phone pane's status screen renders in the light color scheme

#### Scenario: Toggle leaves the control panel chrome unchanged
- **WHEN** the operator switches the theme toggle to Dark
- **THEN** the right-hand control panel chrome is unaffected by the toggle
