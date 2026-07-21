# event-album Specification

## MODIFIED Requirements

### Requirement: Opt-in album mirroring per membership
The system SHALL mirror an event's synced photos into a single PhotoKit album on the device — titled
after the event's (non-null) `name` — when that membership's persisted `EventConfig.saveToAlbum` is
`true` (capability `event-link`). The set placed into the album SHALL be **every** photo the membership
syncs in its participation direction: the **foreign photos it downloads** and/or the **own photos it
uploads** (capability `join-event` direction). When `saveToAlbum` is `false` (the default) the system
SHALL create no album and place no photos. The choice SHALL be a **forward-only runtime toggle**,
changeable in place after join via `reconfigure-membership`: turning it **on** SHALL ensure the album and
mirror photos synced **from that point onward** — already-synced photos SHALL NOT be retroactively
gathered; turning it **off** SHALL stop further placement but SHALL NOT delete the album or clear its
identity map (see *Album identity is remembered per event and survives leave*), so a later on reuses the
same album. Album placement SHALL be **best-effort**: a failure to create the album or to add a given
photo SHALL be logged and SHALL NOT fail, block, or retry the underlying sync (upload or import).

#### Scenario: Album-on mirrors both directions
- **WHEN** a membership has `saveToAlbum = true` and direction `Both`, and the device both downloads a foreign photo and completes an upload of its own photo
- **THEN** both photos are present in the event's album

#### Scenario: Album-off creates nothing
- **WHEN** a membership has `saveToAlbum = false`
- **THEN** no album is created and no photo is placed, for either direction

#### Scenario: Turning the album on adds only photos synced thereafter
- **WHEN** a membership with `saveToAlbum = false` and already-synced photos is reconfigured to `saveToAlbum = true`
- **THEN** the album is ensured and photos synced from that point onward are added, while the already-synced photos are not retroactively gathered

#### Scenario: Turning the album off stops placement without deleting
- **WHEN** a membership with `saveToAlbum = true` is reconfigured to `saveToAlbum = false`
- **THEN** no further photos are placed, and the album and its `eventId → albumLocalId` map entry are left intact

#### Scenario: A placement failure never breaks sync
- **WHEN** adding a photo to the album fails (e.g. the asset was deleted, or the album no longer resolves)
- **THEN** the failure is logged and the upload/import it rode on still succeeds

### Requirement: Album identity is remembered per event and survives leave

The system SHALL persist the created album's PhotoKit `localIdentifier` in a per-event
`eventId → albumLocalId` map held in a **shared store** readable and writable by both the app and the
upload extension. That store SHALL **survive `LeaveEvent.leave()`** — leaving an event SHALL NOT clear
the album map (unlike the event config). On a **re-join** of the same event with `saveToAlbum = true`,
the system SHALL resolve the stored `albumLocalId`: if the album still exists it SHALL be **reused** (so
the prior membership's photos remain gathered); if it no longer resolves (the user deleted it) the
system SHALL create a fresh album and overwrite the map. A user-deleted album SHALL NOT be recreated by a
passive sync cycle; recreation SHALL happen only on an **explicit opt-in act** — a re-join with the box
checked, or a `reconfigure-membership` change that turns the album on. Photos SHALL be added by resolving
the stored `albumLocalId`, never by matching the album title, so a user rename of the album and two events
sharing a name do not misroute placement.

#### Scenario: Re-join reuses the same album
- **WHEN** a device leaves an event whose album exists, then re-joins the same event with `saveToAlbum = true`
- **THEN** the stored `albumLocalId` is reused and newly synced photos are added to the existing album (its earlier photos are retained)

#### Scenario: A deleted album is recreated on re-join
- **WHEN** the user deletes the album, then re-joins the event with `saveToAlbum = true` and the stored id no longer resolves
- **THEN** a fresh album is created and the map is overwritten with its new identifier

#### Scenario: Turning the album on after deletion recreates it
- **WHEN** the user deletes the album, then turns the album on via `reconfigure-membership` and the stored id no longer resolves
- **THEN** a fresh album is created and the map is overwritten with its new identifier

#### Scenario: The album map is not cleared on leave
- **WHEN** `LeaveEvent.leave()` clears the event config
- **THEN** the `eventId → albumLocalId` map entry is preserved for a future re-join
