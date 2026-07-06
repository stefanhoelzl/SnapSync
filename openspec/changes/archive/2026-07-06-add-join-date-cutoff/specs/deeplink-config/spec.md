## MODIFIED Requirements

### Requirement: snapsync:// URL scheme and payload contract

The system SHALL define a custom URL scheme `snapsync` whose config deeplink has the form
`snapsync://config?v=3&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object whose **required** key is `eventId` (a string) and whose
**optional** keys are `autoJoin` (a boolean) and `minPhotoDate` (a string) — the deeplink wire payload
(`EventLinkPayload`). The `eventId` SHALL be a high-entropy **canonical UUID**; possession of it is the
upload capability (the edge endpoint authorizes by event id alone). `autoJoin` SHALL default to `false`
when absent; it is a **dev/test** hint that requests the join gate auto-confirm (see capability
`join-event`). `minPhotoDate` SHALL be absent by default; it is a **dev/test** capture-date cutoff (a UTC
`yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability `photo-date-cutoff`) that, on an auto-confirmed join, forces
a specific cutoff so a headless launch can observe date filtering. Neither `autoJoin` nor `minPhotoDate`
SHALL be emitted by the canonical QR encoder — real invite QRs carry `eventId` only. The upload **host**
SHALL NOT appear in the payload: it is fixed at compile time by the extension's `BackgroundUploadURLBase`
(capability `ios-photokit-upload`). The payload carries **no storage credential** and **no event name** —
the name is not carried in the QR; a joined device fetches it by `eventId` (see *Event name is fetched,
not carried in the deeplink*). `v` SHALL be the integer format version, `3` (the optional `autoJoin` and
`minPhotoDate` keys are additive within `v=3` and do not bump the version). The payload is carried
entirely in the deeplink; there is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `EventLinkPayload` with no optional keys
- **THEN** it is `snapsync://config?v=3&d=<base64url(json)>` and the decoded JSON has the string key
  `eventId` and no other keys (no `autoJoin`, no `minPhotoDate`, no `name`, no host, no credential)

#### Scenario: A dev link carries the autoJoin flag
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true }`
- **THEN** the decode succeeds carrying that `eventId` and `autoJoin == true`

#### Scenario: A dev link carries an explicit cutoff
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "minPhotoDate": "2026-07-06T14:32:11Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `minPhotoDate` cutoff string

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `EventLinkPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive), and **at most** the additional optional keys `autoJoin` (a boolean)
and `minPhotoDate` (a non-empty string). Any deviation — including a version other than `3` (so the
v1/v2 S3 payloads are rejected), a missing/empty `eventId`, a **genuinely unknown** key (anything other
than `eventId`/`autoJoin`/`minPhotoDate`), or an `eventId` that is not a canonical UUID — SHALL produce a
typed failure result, never a partially-populated payload and never a thrown exception that escapes the
decoder. On success the result SHALL carry the `eventId`, the resolved `autoJoin` value (defaulting to
`false`), and the `minPhotoDate` cutoff when present (else absent).

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=3&d=…` URL whose payload carries a single non-empty canonical-UUID
  `eventId` is decoded
- **THEN** the result is a success carrying the `EventLinkPayload` with that exact `eventId`, `autoJoin == false`, and no `minPhotoDate`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a version other than `3` (including a legacy `v=2` S3
  payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a genuinely unknown key
  (other than `eventId`/`autoJoin`/`minPhotoDate`), or an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventLinkPayload`, without throwing

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 deeplink (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" create layer and the user rescans the new event QR

### Requirement: Config source and store seams

The capability SHALL define a persisted, joined-event state type **`EventConfig { eventId: String,
name: String?, minPhotoDate: String? }`** (distinct from the deeplink wire type `EventLinkPayload`):
`eventId` is the joined event; `name` is the human-readable event name, **nullable** because it is
fetched after joining and may not be available yet; `minPhotoDate` is this device's chosen capture-date
cutoff for the event (capability `photo-date-cutoff`), **nullable** (absent = whole-library scope). The
capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<EventConfig?>` — a
level-triggered holder whose current value (the active config, or `null` when none) is always available
synchronously — and a `ConfigStore` command port with `suspend fun save(config: EventConfig)` that
persists the config and updates the source, and `suspend fun clear()` that removes it and updates the
source to `null`. `save` of a config equal to the current one (same `eventId`, `name`, **and**
`minPhotoDate`) SHALL be an idempotent no-op; a `save` differing in any of those SHALL replace it and
emit (a name-only change updates the title without any ledger effect; the switch-reset on an `eventId`
change is orchestrated by the provision path, not this seam). `clear` SHALL remove the persisted config
and set the source to `null`, and SHALL NOT touch the ledger; `clear` when absent SHALL be an idempotent
no-op. Consumers SHALL depend on each port separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfig` (eventId, any name, and any minPhotoDate)
  without waiting for an emission

#### Scenario: Saving a name-only update emits without a switch
- **WHEN** `save` is invoked with the same `eventId` and `minPhotoDate` and a newly-fetched `name`
- **THEN** the persisted config's name is updated and `config` emits, with no ledger reset

#### Scenario: Saving a different event hot-swaps the source
- **WHEN** `save` is invoked with a different `eventId`
- **THEN** the persisted config is replaced and `config` emits the new `EventConfig`

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save` is invoked with a config equal to the current value (same eventId, name, and minPhotoDate)
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the config and nulls the source
- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the persisted config is removed and `config` emits `null`, with no change to the ledger

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfig` (its `eventId`,
optional `name`, and optional `minPhotoDate`) as a single Keychain item under a **shared
keychain-access-group** (paired with an App Group) so the background upload extension can read the same
event config — the extension reads the `eventId` **and** the `minPhotoDate` (the cutoff that scopes its
upload cycle, capability `photo-date-cutoff`). It SHALL seed its `config` `StateFlow` **synchronously** at
construction by reading the Keychain item (mapping a missing item to `null`), and `save` SHALL write the
item and then emit. `clear` SHALL delete the item and then emit `null`; deleting an absent item SHALL be
treated as success. The item SHALL persist across app updates and survive process death.

#### Scenario: Persisted config survives relaunch
- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig` (eventId, name, and minPhotoDate)

#### Scenario: The extension reads the persisted cutoff
- **WHEN** the background upload extension reads the shared Keychain config
- **THEN** it obtains both the `eventId` and the persisted `minPhotoDate` cutoff for scoping its upload cycle

#### Scenario: No config reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

#### Scenario: Cleared config does not survive relaunch
- **WHEN** a config is saved, `clear()` is invoked, the app terminates, and the adapter is
  reconstructed on next launch
- **THEN** `config.value` is `null` (the Keychain item was deleted)

### Requirement: Event name is fetched, not carried in the deeplink

The event `name` SHALL be obtained by `eventId`, never from the QR. On **scan-provision** (a decoded
`EventLinkPayload`), the join gate (capability `join-event`) SHALL fetch `GET /events/:id` for the
event's `name` **and** `createdAt` before the confirm, and provision (save the config, with the name and
the chosen cutoff) only on confirm. On **create**, the create path SHALL route the minted `eventId` into
that **same** join gate (capability `event-creation-ui`), which fetches `GET /events/:id` for the `name`
and `createdAt` and provisions on confirm — the create path itself SHALL save no config directly. In both
paths the fetched `createdAt` SHALL seed the default cutoff (capability `photo-date-cutoff`). The name
SHALL be refreshed by re-fetching `GET /events/:id` on **foreground entry**. A failed or unreachable
fetch SHALL leave the last-known name (or `null`) unchanged and SHALL NOT affect syncing.

#### Scenario: Scan routes to the gate, which fetches name and createdAt
- **WHEN** a valid event QR is scanned
- **THEN** the join gate fetches `GET /events/:id` for the `name` and `createdAt`, seeds the cutoff default from `createdAt`, and provisions the config (name + chosen cutoff) on confirm

#### Scenario: Create routes to the gate, which fetches details
- **WHEN** an event is created and `POST /events` returns `{eventId, name, createdAt}`
- **THEN** the minted `eventId` is routed into the join gate, which fetches `GET /events/:id` for the `name` and `createdAt`, and the config is saved only on confirm

#### Scenario: A failed name fetch does not block the joined sync
- **WHEN** the `GET /events/:id` fetch fails or the device is offline after provisioning
- **THEN** the config's name remains its last-known value (or `null`), and sync proceeds normally, and a
  later foreground refresh may fill the name
