## MODIFIED Requirements

### Requirement: Opt-in album mirroring per membership
The system SHALL mirror an event's synced photos into a single PhotoKit album on the device — titled
after the event's (non-null) `name` — when that membership's persisted `EventConfig.saveToAlbum` is
`true` (capability `event-link`). The set placed into the album SHALL be **every** photo the membership
syncs in its participation direction: the **foreign photos it downloads** and/or the **own photos it
uploads** (capability `join-event` direction). When `saveToAlbum` is `false` (the default) the system
SHALL create no album and place no photos. The choice is **fixed for the membership** — there is no
runtime toggle; a change is a leave-and-rejoin. Album placement SHALL be **best-effort**: a failure to
create the album or to add a given photo SHALL be logged and SHALL NOT fail, block, or retry the
underlying sync (upload or import).

#### Scenario: Album-on mirrors both directions
- **WHEN** a membership has `saveToAlbum = true` and direction `Both`, and the device both downloads a foreign photo and completes an upload of its own photo
- **THEN** both photos are present in the event's album

#### Scenario: Album-off creates nothing
- **WHEN** a membership has `saveToAlbum = false`
- **THEN** no album is created and no photo is placed, for either direction

#### Scenario: A placement failure never breaks sync
- **WHEN** adding a photo to the album fails (e.g. the asset was deleted, or the album no longer resolves)
- **THEN** the failure is logged and the upload/import it rode on still succeeds
