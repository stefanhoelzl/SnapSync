## MODIFIED Requirements

### Requirement: The reconfigure surface reuses the join controls, pre-filled, committed atomically

The settings action SHALL open a full-screen reconfigure surface that composes the **same** design-system
controls the join surface uses — the Share-section switch header with its cutoff-preset selector
(capability `design-system`) and the album opt-in toggle — **pre-filled** with the membership's current
`direction`, `minPhotoDate`, and `saveToAlbum`, above a **read-only** event-name header for context.
Edits SHALL be committed **atomically** on a **Save** action (exactly one `ConfigStore.save`) and
**discarded** on **Cancel**.

Opening and closing the surface SHALL be **client-side navigation** that touches no port until Save, and
SHALL NOT introduce a new `UiState` family. Which surface the joined layer is showing SHALL be expressed
as a selection **within** the joined state, so that the state describes what is on screen while opening
and closing remain container-local acts reaching no use case and no command — the property that made a
family the wrong shape is preserved, and the joined layer's health, pending switch and membership are not
duplicated to model a surface that is still the joined layer.

The pre-filled values SHALL be seeded and resolved by the presentation container from the persisted
membership — the same config source the invite URL derives from — and carried as reduced state, so the
surface renders from the state rather than from values the screen holds. Cancel SHALL return them to that
seed.

#### Scenario: The surface opens pre-filled with current settings
- **WHEN** a `Both` membership with `saveToAlbum = true` opens the reconfigure surface
- **THEN** both participation switches are on, the album toggle is on, the cutoff selector shows the
  current cutoff, and the event name is shown read-only

#### Scenario: Cancel discards edits
- **WHEN** the member changes controls on the surface and taps Cancel
- **THEN** no config write occurs and the membership's settings are unchanged

#### Scenario: Save commits once
- **WHEN** the member changes two controls and taps Save
- **THEN** a single whole-object `EventConfig` save is performed carrying both changes

#### Scenario: Opening the surface touches no port
- **WHEN** the member taps the settings action
- **THEN** the joined state's surface selection changes and no port is called, no command is dispatched,
  and no config is read from storage anew

#### Scenario: The state says which surface is showing
- **WHEN** the reconfigure surface is open
- **THEN** the joined state says so, and a consumer rendering from that state alone shows the reconfigure
  surface rather than the status surface
