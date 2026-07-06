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
**only optional** key is `autoJoin` (a boolean) — the deeplink wire payload (`EventLinkPayload`). The
`eventId` SHALL be a high-entropy **canonical UUID**; possession of it is the upload capability (the
edge endpoint authorizes by event id alone). `autoJoin` SHALL default to `false` when absent; it is a
**dev/test** hint that requests the join gate auto-confirm (see capability `join-event`) and SHALL NOT
be emitted by the canonical QR encoder — real invite QRs carry `eventId` only. The upload **host**
SHALL NOT appear in the payload: it is fixed at compile time by the extension's
`BackgroundUploadURLBase` (capability `ios-photokit-upload`). The payload carries **no storage
credential** and **no event name** — the name is not carried in the QR; a joined device fetches it by
`eventId` (see *Event name is fetched, not carried in the deeplink*). `v` SHALL be the integer format
version, `3` (the optional `autoJoin` key is additive within `v=3` and does not bump the version). The
payload is carried entirely in the deeplink; there is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `EventLinkPayload` with no `autoJoin`
- **THEN** it is `snapsync://config?v=3&d=<base64url(json)>` and the decoded JSON has the string key
  `eventId` and no other keys (no `autoJoin`, no `name`, no host, no credential)

#### Scenario: A dev link carries the autoJoin flag
- **WHEN** a config deeplink is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true }`
- **THEN** the decode succeeds carrying that `eventId` and `autoJoin == true`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `EventLinkPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive), and **at most** the additional optional boolean key `autoJoin`.
Any deviation — including a version other than `3` (so the v1/v2 S3 payloads are rejected), a
missing/empty `eventId`, a **genuinely unknown** key (anything other than `eventId`/`autoJoin`), or an
`eventId` that is not a canonical UUID — SHALL produce a typed failure result, never a
partially-populated payload and never a thrown exception that escapes the decoder. On success the
result SHALL carry both the `eventId` and the resolved `autoJoin` value (defaulting to `false`).

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=3&d=…` URL whose payload carries a single non-empty canonical-UUID
  `eventId` is decoded
- **THEN** the result is a success carrying the `EventLinkPayload` with that exact `eventId` and `autoJoin == false`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a version other than `3` (including a legacy `v=2` S3
  payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a genuinely unknown key
  (other than `eventId`/`autoJoin`), or an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventLinkPayload`, without throwing

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 deeplink (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" create layer and the user rescans the new event QR

### Requirement: Config source and store seams

The capability SHALL define a persisted, joined-event state type **`EventConfig { eventId: String,
name: String? }`** (distinct from the deeplink wire type `EventLinkPayload`): `eventId` is the joined
event; `name` is the human-readable event name, **nullable** because it is fetched after joining and
may not be available yet. The capability SHALL define a `ConfigSource` state port exposing
`config: StateFlow<EventConfig?>` — a level-triggered holder whose current value (the active config, or
`null` when none) is always available synchronously — and a `ConfigStore` command port with
`suspend fun save(config: EventConfig)` that persists the config and updates the source, and
`suspend fun clear()` that removes it and updates the source to `null`. `save` of a config equal to the
current one (same `eventId` **and** `name`) SHALL be an idempotent no-op; a `save` differing in
`eventId` **or** `name` SHALL replace it and emit (a name-only change updates the title without any
ledger effect; the switch-reset on an `eventId` change is orchestrated by the provision path, not this
seam). `clear` SHALL remove the persisted config and set the source to `null`, and SHALL NOT touch the
ledger; `clear` when absent SHALL be an idempotent no-op. Consumers SHALL depend on each port
separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfig` (eventId and any name)
  without waiting for an emission

#### Scenario: Saving a name-only update emits without a switch
- **WHEN** `save` is invoked with the same `eventId` and a newly-fetched `name`
- **THEN** the persisted config's name is updated and `config` emits, with no ledger reset

#### Scenario: Saving a different event hot-swaps the source
- **WHEN** `save` is invoked with a different `eventId`
- **THEN** the persisted config is replaced and `config` emits the new `EventConfig`

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save` is invoked with a config equal to the current value (same eventId and name)
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the config and nulls the source
- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the persisted config is removed and `config` emits `null`, with no change to the ledger

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfig` (its `eventId` and
optional `name`) as a single Keychain item under a **shared keychain-access-group** (paired with an
App Group) so the background upload extension can read the same event config (the extension reads only
the `eventId`). It SHALL seed its `config` `StateFlow` **synchronously** at construction by reading the
Keychain item (mapping a missing item to `null`), and `save` SHALL write the item and then emit.
`clear` SHALL delete the item and then emit `null`; deleting an absent item SHALL be treated as
success. The item SHALL persist across app updates and survive process death.

#### Scenario: Persisted config survives relaunch
- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig` (eventId and name)

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
`EventLinkPayload`), the provision path SHALL save `EventConfig(eventId, name = null)` **immediately**
(joining SHALL NOT block on the name — it is cosmetic), then perform a **best-effort** `GET /events/:id`
and, on success, `save(EventConfig(eventId, name))` to fill the name. On **create**, the returned
`POST /events` body already carries the name, so the create path SHALL save `EventConfig(eventId, name)`
directly with **no** fetch. The name SHALL be refreshed by re-fetching `GET /events/:id` on **foreground
entry**. A failed or unreachable fetch SHALL leave the last-known name (or `null`) unchanged and SHALL
NOT affect joining or syncing.

#### Scenario: Scan provisions immediately, name fills in after
- **WHEN** a valid event QR is scanned
- **THEN** `EventConfig(eventId, name = null)` is saved at once (the join proceeds), and a successful
  `GET /events/:id` afterward updates the config to carry the fetched `name`

#### Scenario: Create saves the name without a fetch
- **WHEN** an event is created and `POST /events` returns `{eventId, name, createdAt}`
- **THEN** `EventConfig(eventId, name)` is saved directly, with no `GET /events/:id` call

#### Scenario: A failed name fetch does not block joining
- **WHEN** the `GET /events/:id` fetch fails or the device is offline after a scan
- **THEN** the config remains `EventConfig(eventId, name = null)`, the join and sync proceed normally,
  and a later foreground refresh may fill the name

