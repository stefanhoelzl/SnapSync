## MODIFIED Requirements

### Requirement: The app is the sole album creator, created eagerly on the permission grant

The **app process** SHALL be the only creator of the album. It SHALL create the album eagerly when
photo-library permission transitions to `GRANTED` (or immediately at provision if already granted) for a
membership whose `saveToAlbum` is `true` and whose album does not yet exist. The membership's opt-in
gate SHALL be the coordinator's **own leading guard** — `AlbumCoordinator.ensureAlbum(eventId, name,
saveToAlbum, granted)` is a no-op returning `null` for an ungranted or opted-out
membership (`granted` defaults `true` for callers that run *because* access was granted) — so its
callers (the `compose/`-installed permission-grant subscription and the `flow/Provision` trigger,
which passes the access fact) call it unconditionally with the membership's facts and no caller
can forget the rule. The guard SHALL NOT test the `name`: a membership's name is required and non-null
(capability `event-link`), so a nameless membership is not a representable state and a clause guarding
against one would be an unreachable branch inviting the reader to believe otherwise. Neither the app's
download path nor the upload extension SHALL ever **create** the
album — they SHALL only **add** to an already-created album. Because syncing requires the same
full-library permission, creating on the grant guarantees the album exists before the first synced photo
is produced, so no two processes race to create it. A membership that never syncs a photo MAY therefore
have an empty album; this is acceptable.

#### Scenario: Album created on the grant transition
- **WHEN** a device is joined with `saveToAlbum = true` and photo permission transitions from not-determined to granted
- **THEN** the app creates the event's album and records its identifier before any upload or download runs

#### Scenario: Album created at provision when already granted
- **WHEN** a join with `saveToAlbum = true` is confirmed while photo permission is already granted
- **THEN** the app creates the album as part of provisioning

#### Scenario: The extension never creates the album
- **WHEN** the upload extension runs a cycle for a `saveToAlbum` membership whose album has not yet been created
- **THEN** the extension adds nothing and does not create an album; creation is left to the app
