## ADDED Requirements

### Requirement: snapsync:// URL scheme and payload contract

The system SHALL define a custom URL scheme `snapsync` whose config deeplink has the form
`snapsync://config?v=1&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object with exactly the keys `bucket`, `region`, `endpoint`,
`accessKeyId`, and `secretAccessKey` (all strings). `v` SHALL be the integer format version,
`1` for this contract. The full `S3Config`, including the secret access key, is carried in the
deeplink; there is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `S3Config`
- **THEN** it is `snapsync://config?v=1&d=<base64url(json)>` and the decoded JSON has exactly
  the five string keys `bucket`, `region`, `endpoint`, `accessKeyId`, `secretAccessKey`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `S3Config` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 1`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with all five keys present and **non-empty**. Any deviation SHALL produce a
failure result, never a partially-populated config and never a thrown exception that escapes
the decoder. The decoder SHALL NOT contact S3 or validate credential correctness.

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=1&d=…` URL whose payload carries all five non-empty fields is decoded
- **THEN** the result is a success carrying the `S3Config` with those exact field values

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a non-1 version, undecodable base64url, non-JSON bytes,
  a missing key, or an empty field
- **THEN** the decoder returns a typed failure and no `S3Config`, without throwing

#### Scenario: Credentials are not probed
- **WHEN** a structurally-valid payload carrying wrong credentials is decoded
- **THEN** decoding succeeds (correctness is discovered only at upload time), with no network access

### Requirement: Config source and store seams

The capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<S3Config?>`
— a level-triggered holder whose current value (the active config, or `null` when none) is always
available synchronously — and a `ConfigStore` command port `suspend fun save(config: S3Config)`
that persists the config and updates the source. `save` of a config equal to the current one
SHALL be an idempotent no-op; `save` of a different config SHALL replace it silently (the ledger
is not touched). Consumers SHALL depend on each port separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `S3Config` without waiting for an emission

#### Scenario: Saving a new config hot-swaps the source
- **WHEN** `save(newConfig)` is invoked with a config different from the current one
- **THEN** the persisted config is replaced and `config` emits `newConfig`, with no restart and no
  change to the ledger

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save(config)` is invoked with a config equal to the current value
- **THEN** no change and no redundant emission occur

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `S3Config` as a single
Keychain item under a **shared keychain-access-group** (paired with an App Group) so a future
background upload extension can read the same credentials. It SHALL seed its `config` `StateFlow`
**synchronously** at construction by reading the Keychain item (mapping a missing item to `null`),
and `save` SHALL write the Keychain item and then emit. The item SHALL persist across app updates
and survive process death.

#### Scenario: Persisted config survives relaunch
- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `S3Config`

#### Scenario: No config reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

### Requirement: Authoritative QR generator

The repository SHALL provide a Gradle task that is the single authoritative encoder of the
config deeplink: given the five `S3Config` fields, it SHALL emit the
`snapsync://config?v=1&d=<base64url(json)>` URL and render a scannable QR-code PNG. The task SHALL
read field values (including the secret) from environment variables and/or a gitignored
`local.properties`, and SHALL NOT commit secrets. The URL it emits SHALL be byte-compatible with
what the pure decoder accepts.

#### Scenario: Task emits a decodable URL and a QR image
- **WHEN** the generator task runs with the five fields supplied via env/local.properties
- **THEN** it writes a QR PNG and prints a `snapsync://config?v=1&d=…` URL that the pure decoder
  decodes back to the same five field values

#### Scenario: Secrets are never committed
- **WHEN** the generator reads the secret access key
- **THEN** it is sourced from env or gitignored `local.properties` and no secret is written to a
  tracked file
