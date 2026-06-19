## ADDED Requirements

### Requirement: Portrait-only orientation

The iOS app SHALL be presented in upright portrait orientation only. Its `Info.plist` SHALL declare both `UISupportedInterfaceOrientations` and `UISupportedInterfaceOrientations~ipad` as exactly `[UIInterfaceOrientationPortrait]`, so that on both iPhone and iPad — the app is a universal binary (`TARGETED_DEVICE_FAMILY = "1,2"`) — the UI never rotates to landscape or to upside-down portrait. The lock SHALL be the static plist declaration; no runtime per-view-controller orientation override is used.

#### Scenario: Rotating an iPhone to landscape does not rotate the UI
- **WHEN** the app is running on an iPhone and the device is turned to a landscape orientation
- **THEN** the UI stays in upright portrait and does not rotate to landscape or upside-down

#### Scenario: Rotating an iPad does not rotate the UI
- **WHEN** the app is running on an iPad and the device is turned to landscape or rotated 180°
- **THEN** the UI stays in upright portrait and does not rotate to landscape or upside-down portrait
