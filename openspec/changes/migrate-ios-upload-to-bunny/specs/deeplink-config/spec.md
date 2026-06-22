## MODIFIED Requirements

### Requirement: snapsync:// URL scheme and payload contract

The system SHALL define a custom URL scheme `snapsync` whose config deeplink has the form
`snapsync://config?v=3&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object with exactly one key, `eventId` (a string) — the runtime
config payload (`EventConfigPayload`). The `eventId` SHALL be a high-entropy **canonical UUID**;
possession of it is the upload capability (the edge endpoint authorizes by event id alone). The
upload **host** SHALL NOT appear in the payload: it is fixed at compile time by the extension's
`BackgroundUploadURLBase` (capability `ios-background-upload`), so carrying it at runtime would be
redundant and could drift from the baked value. The payload carries **no storage credential** (the
v1 S3 keys are gone — auth moved to the edge endpoint). `v` SHALL be the integer format version,
bumped to `3` for this single-key contract. The payload is carried entirely in the deeplink; there
is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `EventConfigPayload`
- **THEN** it is `snapsync://config?v=3&d=<base64url(json)>` and the decoded JSON has exactly the
  one string key `eventId` and no other keys

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `EventConfigPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with exactly the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive). Any deviation — including a version other than `3` (so the
v1/v2 S3 payloads are rejected), a missing/empty/extra key, or an `eventId` that is not a canonical
UUID — SHALL produce a typed failure result, never a partially-populated payload and never a thrown
exception that escapes the decoder.

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=3&d=…` URL whose payload carries a single non-empty canonical-UUID
  `eventId` is decoded
- **THEN** the result is a success carrying the `EventConfigPayload` with that exact `eventId`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a version other than `3` (including a legacy `v=2` S3
  payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a stray extra key, or
  an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventConfigPayload`, without throwing

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 deeplink (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" setup gate and the user rescans the new event QR

### Requirement: Config source and store seams

The capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<EventConfigPayload?>`
— a level-triggered holder whose current value (the active payload, or `null` when none) is always
available synchronously — and a `ConfigStore` command port `suspend fun save(config: EventConfigPayload)`
that persists the payload and updates the source. `save` of a payload equal to the current one
SHALL be an idempotent no-op; `save` of a different payload SHALL replace it silently (the ledger
is not touched). Consumers SHALL depend on each port separately. Combining the `EventConfigPayload`
with the compile-time upload host and the device id into a full upload destination is the
responsibility of the consuming composition root, not these seams.

#### Scenario: Source seeds the current payload synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a payload is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfigPayload` without waiting for an emission

#### Scenario: Saving a new payload hot-swaps the source
- **WHEN** `save(newPayload)` is invoked with a payload different from the current one
- **THEN** the persisted payload is replaced and `config` emits `newPayload`, with no restart and no
  change to the ledger

#### Scenario: Saving an identical payload is a no-op
- **WHEN** `save(payload)` is invoked with a payload equal to the current value
- **THEN** no change and no redundant emission occur

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `EventConfigPayload` as a single
Keychain item under a **shared keychain-access-group** (paired with an App Group) so the
background upload extension can read the same event config. It SHALL seed its `config` `StateFlow`
**synchronously** at construction by reading the Keychain item (mapping a missing item to `null`),
and `save` SHALL write the Keychain item and then emit. The item SHALL persist across app updates
and survive process death.

#### Scenario: Persisted payload survives relaunch
- **WHEN** a payload is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfigPayload`

#### Scenario: No payload reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

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
