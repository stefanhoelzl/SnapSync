# deeplink-config Specification

## Purpose

The `snapsync://` config deeplink: its URL scheme and payload contract, a pure structural
decoder, the `ConfigSource`/`ConfigStore` seams, the iOS Keychain-backed store, and the
authoritative QR generator that is the single encoder of the deeplink.
## Requirements
### Requirement: snapsync:// URL scheme and payload contract

The system SHALL define a custom URL scheme `snapsync` whose config deeplink has the form
`snapsync://config?v=3&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object whose **required** key is `eventId` (a string) and whose
**optional** keys are `autoJoin` (a boolean), `minPhotoDate` (a string), `direction` (a string), and
`saveToAlbum` (a boolean) — the deeplink wire payload (`EventLinkPayload`). The `eventId` SHALL be a
high-entropy **canonical UUID**; possession of it is the upload capability (the edge endpoint authorizes
by event id alone). `autoJoin` SHALL default to `false` when absent; it is a **dev/test** hint that
requests the join gate auto-confirm (see capability `join-event`). `minPhotoDate` SHALL be absent by
default; it is a **dev/test** capture-date cutoff (a UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability
`photo-date-cutoff`) that, on an auto-confirmed join, forces a specific cutoff so a headless launch can
observe date filtering. `direction` SHALL be absent by default; it is a **dev/test**
participation-direction override — one of `both`, `upload`, or `download` — that, on an auto-confirmed
join, forces the joined membership's direction (see capability `join-event`). `saveToAlbum` SHALL be
absent by default; it is a **dev/test** override (a boolean) that, on an auto-confirmed join, forces
whether the membership gathers its synced photos into an event album (capability `event-album`), so a
headless launch can exercise album placement without an interactive tap. None of `autoJoin`,
`minPhotoDate`, `direction`, or `saveToAlbum` SHALL be emitted by the canonical QR encoder — real invite
QRs carry `eventId` only. The upload **host** SHALL NOT appear in the payload: it is fixed at compile time
by the extension's `BackgroundUploadURLBase` (capability `ios-photokit-upload`). The payload carries **no
storage credential** and **no event name** — the name is not carried in the QR; a joined device fetches
it by `eventId` (see *Event name is fetched, not carried in the deeplink*). `v` SHALL be the integer
format version, `3` (the optional `autoJoin`, `minPhotoDate`, `direction`, and `saveToAlbum` keys are
additive within `v=3` and do not bump the version). The payload is carried entirely in the deeplink;
there is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `EventLinkPayload` with no optional keys
- **THEN** it is `snapsync://config?v=3&d=<base64url(json)>` and the decoded JSON has the string key
  `eventId` and no other keys (no `autoJoin`, no `minPhotoDate`, no `direction`, no `saveToAlbum`, no
  `name`, no host, no credential)

#### Scenario: A dev link carries the autoJoin flag
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true }`
- **THEN** the decode succeeds carrying that `eventId` and `autoJoin == true`

#### Scenario: A dev link carries an explicit cutoff
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "minPhotoDate": "2026-07-06T14:32:11Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `minPhotoDate` cutoff string

#### Scenario: A dev link carries an explicit direction override
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "direction": "download" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `direction` override `download`

#### Scenario: A dev link carries an explicit saveToAlbum override
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "saveToAlbum": true }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `saveToAlbum` override `true`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `EventLinkPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive), and **at most** the additional optional keys `autoJoin` (a boolean),
`minPhotoDate` (a non-empty string), `direction` (one of the strings `both`, `upload`, `download`), and
`saveToAlbum` (a boolean). Any deviation — including a version other than `3` (so the v1/v2 S3 payloads
are rejected), a missing/empty `eventId`, a **genuinely unknown** key (anything other than
`eventId`/`autoJoin`/`minPhotoDate`/`direction`/`saveToAlbum`), a `direction` outside the allowed set, or
an `eventId` that is not a canonical UUID — SHALL produce a typed failure result, never a
partially-populated payload and never a thrown exception that escapes the decoder. On success the result
SHALL carry the `eventId`, the resolved `autoJoin` value (defaulting to `false`), the `minPhotoDate`
cutoff when present (else absent), the `direction` override when present (else absent), and the
`saveToAlbum` override when present (else absent).

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=3&d=…` URL whose payload carries a single non-empty canonical-UUID
  `eventId` is decoded
- **THEN** the result is a success carrying the `EventLinkPayload` with that exact `eventId`, `autoJoin == false`, no `minPhotoDate`, no `direction`, and no `saveToAlbum`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a version other than `3` (including a legacy `v=2` S3
  payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a genuinely unknown key
  (other than `eventId`/`autoJoin`/`minPhotoDate`/`direction`/`saveToAlbum`), a `direction` outside
  `both`/`upload`/`download`, or an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventLinkPayload`, without throwing

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 deeplink (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" create layer and the user rescans the new event QR

### Requirement: Config source and store seams

The capability SHALL define a persisted, joined-event state type **`EventConfig { eventId: String,
name: String, minPhotoDate: String?, direction: Direction, saveToAlbum: Boolean }`** (distinct from the
deeplink wire type `EventLinkPayload`): `eventId` is the joined event; `name` is the human-readable event
name — a **required, non-null** value (the join gate only provisions from a loaded phase that carries a
name, capability `join-event`; a legacy item persisted without a name decodes to an empty string and is
refreshed on foreground, so the type is never null); `minPhotoDate` is this device's chosen capture-date
cutoff for the event (capability `photo-date-cutoff`), **nullable** (absent = whole-library scope);
`direction` is this device's chosen participation direction — a `Direction` enum with values `Both`,
`UploadOnly`, `DownloadOnly` — that **SHALL default to `Both`** when absent from persisted or decoded
state; `saveToAlbum` is whether this membership gathers its synced photos into an event album (capability
`event-album`) and **SHALL default to `false`** when absent (so an `EventConfig` persisted before this
field existed reads as `false`, today's no-album behavior). The capability SHALL define a `ConfigSource`
state port exposing `config: StateFlow<EventConfig?>` — a level-triggered holder whose current value (the
active config, or `null` when none) is always available synchronously — and a `ConfigStore` command port
with `suspend fun save(config: EventConfig)` that persists the config and updates the source, and
`suspend fun clear()` that removes it and updates the source to `null`. `save` of a config equal to the
current one (same `eventId`, `name`, `minPhotoDate`, `direction`, **and** `saveToAlbum`) SHALL be an
idempotent no-op; a `save` differing in any of those SHALL replace it and emit (a name-only change updates
the title without any ledger effect; the switch-reset on an `eventId` change is orchestrated by the
provision path, not this seam). `clear` SHALL remove the persisted config and set the source to `null`,
and SHALL NOT touch the ledger, **and SHALL NOT clear the event-album map** (capability `event-album`,
which persists `eventId → albumLocalId` in a separate store that survives leave); `clear` when absent
SHALL be an idempotent no-op. Consumers SHALL depend on each port separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfig` (eventId, name, any minPhotoDate, its direction, and its saveToAlbum) without waiting for an emission

#### Scenario: A pre-existing config without saveToAlbum reads as false
- **WHEN** a `ConfigSource` is constructed over a persisted `EventConfig` serialized before the `saveToAlbum` field existed
- **THEN** `config.value.saveToAlbum` is `false` (the default), preserving today's no-album behavior

#### Scenario: A pre-existing config without a name decodes non-null
- **WHEN** a `ConfigSource` is constructed over a persisted `EventConfig` serialized without a name
- **THEN** `config.value.name` is a non-null empty string (refreshed on the next foreground fetch), never a decode error

#### Scenario: Saving a name-only update emits without a switch
- **WHEN** `save` is invoked with the same `eventId`, `minPhotoDate`, `direction`, and `saveToAlbum` and a newly-fetched `name`
- **THEN** the persisted config's name is updated and `config` emits, with no ledger reset

#### Scenario: Saving a different event hot-swaps the source
- **WHEN** `save` is invoked with a different `eventId`
- **THEN** the persisted config is replaced and `config` emits the new `EventConfig`

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save` is invoked with a config equal to the current value (same eventId, name, minPhotoDate, direction, and saveToAlbum)
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the config but keeps the album map
- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the persisted config is removed and `config` emits `null`, with no change to the ledger and no change to the event-album map

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfig` (its `eventId`,
`name`, optional `minPhotoDate`, its `direction`, and its `saveToAlbum`) as a single Keychain item under
a **shared keychain-access-group** (paired with an App Group) so the background upload extension can read
the same event config — the extension reads the `eventId`, the `minPhotoDate` (the cutoff that scopes its
upload cycle, capability `photo-date-cutoff`), **and the `saveToAlbum` flag** (to decide whether to add
completed uploads to the event album, capability `event-album`). It SHALL seed its `config` `StateFlow`
**synchronously** at construction by reading the Keychain item (mapping a missing item to `null`), and
`save` SHALL write the item and then emit. `clear` SHALL delete the item and then emit `null`; deleting an
absent item SHALL be treated as success. Deserialization SHALL ignore unknown keys, so an item written
before the `saveToAlbum`/`direction` fields existed decodes with `saveToAlbum = false` and
`direction = Both`, and an item lacking a `name` decodes to an empty non-null name. The item SHALL persist
across app updates and survive process death.

#### Scenario: Persisted config survives relaunch
- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig` (eventId, name, minPhotoDate, direction, and saveToAlbum)

#### Scenario: The extension reads the persisted album flag
- **WHEN** the background upload extension reads the shared Keychain config
- **THEN** it obtains the `eventId`, the persisted `minPhotoDate` cutoff, and the `saveToAlbum` flag for scoping its upload cycle and album placement

#### Scenario: A legacy item without the new fields decodes to defaults
- **WHEN** the adapter reads a Keychain item serialized before the `saveToAlbum` field existed
- **THEN** the decoded `EventConfig` has `saveToAlbum = false` (and `direction = Both`, and a non-null name) and no error is raised

#### Scenario: No config reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

#### Scenario: Cleared config does not survive relaunch
- **WHEN** a config is saved, `clear()` is invoked, the app terminates, and the adapter is
  reconstructed on next launch
- **THEN** `config.value` is `null` (the Keychain item was deleted)

### Requirement: Authoritative QR generator

The repository SHALL provide a Gradle task that is the single authoritative encoder of the
config deeplink: given an `eventId`, it SHALL emit the
`snapsync://config?v=3&d=<base64url(json)>` URL and render a scannable QR-code PNG. The task SHALL
read the `eventId` value from an environment variable and/or a gitignored `local.properties`. The
URL it emits SHALL be byte-compatible with what the pure decoder accepts. The generator SHALL NOT
take or emit an upload host/`endpoint` or any storage credential.

#### Scenario: Task emits a decodable URL and a QR image
- **WHEN** the generator task runs with an `eventId` supplied via env/local.properties
- **THEN** it writes a QR PNG and prints a `snapsync://config?v=3&d=…` URL that the pure decoder
  decodes back to the same `eventId`

#### Scenario: Generator emits no host or credential
- **WHEN** the generator runs
- **THEN** the emitted URL carries only `eventId` — no upload host/`endpoint` and no storage credential

### Requirement: Event name is fetched, not carried in the deeplink

The event `name` SHALL be obtained by `eventId`, never from the QR. On **scan-provision** (a decoded
`EventLinkPayload`), the join gate (capability `join-event`) SHALL fetch `GET /events/:id` for the
event's `name` **and** `createdAt` before the confirm, and provision (save the config, with the name and
the chosen cutoff) only on confirm. On **create**, the create path SHALL route the minted `eventId` into
that **same** join gate (capability `event-creation-ui`), which fetches `GET /events/:id` for the `name`
and `createdAt` and provisions on confirm — the create path itself SHALL save no config directly. In both
paths the fetched `createdAt` SHALL seed the default cutoff (capability `photo-date-cutoff`). The event
`name` is **required and non-null**: the join gate treats a details response lacking a name as a
retryable failure, never a loaded phase, so a provisioned `EventConfig` always carries a real name (the
backend enforces name-required on create, capability `event-creation`). The name SHALL be refreshed by
re-fetching `GET /events/:id` on **foreground entry**. A failed or unreachable fetch SHALL leave the
last-known name unchanged and SHALL NOT affect syncing.

#### Scenario: Scan routes to the gate, which fetches name and createdAt
- **WHEN** a valid event QR is scanned
- **THEN** the join gate fetches `GET /events/:id` for the `name` and `createdAt`, seeds the cutoff default from `createdAt`, and provisions the config (non-null name + chosen cutoff) on confirm

#### Scenario: Create routes to the gate, which fetches details
- **WHEN** an event is created and `POST /events` returns `{eventId, name, createdAt}`
- **THEN** the minted `eventId` is routed into the join gate, which fetches `GET /events/:id` for the `name` and `createdAt`, and the config is saved only on confirm

#### Scenario: A failed name fetch does not block the joined sync
- **WHEN** the `GET /events/:id` fetch fails or the device is offline after provisioning
- **THEN** the config's name remains its last-known value, and sync proceeds normally, and a later foreground refresh may update the name

### Requirement: Switching events leaves the previous event first

The provisioning flow SHALL fire a best-effort backend leave of the previous event before persisting a
new event's config, whenever a valid config deeplink provisions an event whose `eventId` **differs**
from the currently provisioned one (a switch). That leave issues
`DELETE /events/<previousEventId>/devices/<deviceId>` via the same `LeaveNotifier` the explicit Leave
uses. The previous `eventId` SHALL be
read before it is replaced. Provisioning a deeplink for the **same** event that is already configured
SHALL remain an idempotent no-op and SHALL NOT fire a leave. The backend leave SHALL be best-effort — a
failure SHALL NOT prevent the switch — so the device always ends up provisioned to the new event. In
this change the switch fires the leave **without** a confirmation dialog (the leave-confirm-on-switch
dialog is a separate change).

#### Scenario: Provisioning a different event leaves the previous one

- **WHEN** a config deeplink provisions an `eventId` different from the currently configured event
- **THEN** the flow issues `DELETE /events/<previousEventId>/devices/<deviceId>` best-effort, then persists the new event's config

#### Scenario: Re-provisioning the same event fires no leave

- **WHEN** a config deeplink provisions the `eventId` already configured
- **THEN** provisioning is an idempotent no-op and no backend leave is issued

#### Scenario: A failed switch-leave still switches

- **WHEN** the previous-event `DELETE` fails during a switch
- **THEN** the failure is logged and the new event's config is still persisted (the device is provisioned to the new event)

