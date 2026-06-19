# deeplink-config Specification

## Purpose

The `snapsync://` config deeplink: its URL scheme and payload contract, a pure structural
decoder, the `ConfigSource`/`ConfigStore` seams, the iOS Keychain-backed store, and the
authoritative QR generator that is the single encoder of the deeplink.
## Requirements
### Requirement: snapsync:// URL scheme and payload contract

The system SHALL define a custom URL scheme `snapsync` whose config deeplink has the form
`snapsync://config?v=1&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object with exactly the keys `bucket`, `region`, `accessKeyId`,
and `secretAccessKey` (all strings) — the runtime config payload (`S3ConfigPayload`). The upload
**host** SHALL NOT appear in the payload: it is fixed at compile time by the extension's
`BackgroundUploadURLBase` (capability `ios-background-upload`), so carrying it at runtime would be
redundant and could drift from the baked value. `v` SHALL be the integer format version, bumped to
`2` for this four-key contract. The payload, including the secret access key, is carried entirely
in the deeplink; there is no server, token, or Universal Link.

#### Scenario: Canonical config URL shape
- **WHEN** a config deeplink is constructed for an `S3ConfigPayload`
- **THEN** it is `snapsync://config?v=2&d=<base64url(json)>` and the decoded JSON has exactly
  the four string keys `bucket`, `region`, `accessKeyId`, `secretAccessKey` and no `endpoint`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw `snapsync://` URL string into either a valid `S3ConfigPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the scheme is
`snapsync` and host/path is `config`; `v == 2`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with all four keys (`bucket`, `region`, `accessKeyId`, `secretAccessKey`) present
and **non-empty**. Any deviation SHALL produce a failure result, never a partially-populated
payload and never a thrown exception that escapes the decoder. The decoder SHALL NOT contact S3 or
validate credential correctness.

#### Scenario: Well-formed payload decodes
- **WHEN** a `snapsync://config?v=2&d=…` URL whose payload carries all four non-empty fields is decoded
- **THEN** the result is a success carrying the `S3ConfigPayload` with those exact field values

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a wrong scheme/host, a version other than `2`, undecodable base64url, non-JSON
  bytes, a missing key, an empty field, or a stray `endpoint` key
- **THEN** the decoder returns a typed failure and no `S3ConfigPayload`, without throwing

#### Scenario: Credentials are not probed
- **WHEN** a structurally-valid payload carrying wrong credentials is decoded
- **THEN** decoding succeeds (correctness is discovered only at upload time), with no network access

### Requirement: Config source and store seams

The capability SHALL define a `ConfigSource` state port exposing `config: StateFlow<S3ConfigPayload?>`
— a level-triggered holder whose current value (the active payload, or `null` when none) is always
available synchronously — and a `ConfigStore` command port `suspend fun save(config: S3ConfigPayload)`
that persists the payload and updates the source. `save` of a payload equal to the current one
SHALL be an idempotent no-op; `save` of a different payload SHALL replace it silently (the ledger
is not touched). Consumers SHALL depend on each port separately. Combining an `S3ConfigPayload`
with the compile-time upload host into the full `S3Config` is the responsibility of the consuming
composition root, not these seams.

#### Scenario: Source seeds the current payload synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a payload is already persisted
- **THEN** `config.value` immediately reflects the persisted `S3ConfigPayload` without waiting for an emission

#### Scenario: Saving a new payload hot-swaps the source
- **WHEN** `save(newPayload)` is invoked with a payload different from the current one
- **THEN** the persisted payload is replaced and `config` emits `newPayload`, with no restart and no
  change to the ledger

#### Scenario: Saving an identical payload is a no-op
- **WHEN** `save(payload)` is invoked with a payload equal to the current value
- **THEN** no change and no redundant emission occur

### Requirement: iOS Keychain-backed config store

The capability SHALL provide an iOS adapter (`iosMain`) implementing both `ConfigSource` and
`ConfigStore` against the iOS Keychain. It SHALL store the serialized `S3ConfigPayload` as a single
Keychain item under a **shared keychain-access-group** (paired with an App Group) so the
background upload extension can read the same credentials. It SHALL seed its `config` `StateFlow`
**synchronously** at construction by reading the Keychain item (mapping a missing item to `null`),
and `save` SHALL write the Keychain item and then emit. The item SHALL persist across app updates
and survive process death.

#### Scenario: Persisted payload survives relaunch
- **WHEN** a payload is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `S3ConfigPayload`

#### Scenario: No payload reads as null
- **WHEN** the adapter is constructed with no Keychain item present
- **THEN** `config.value` is `null`

### Requirement: Authoritative QR generator

The repository SHALL provide a Gradle task that is the single authoritative encoder of the
config deeplink: given the four `S3ConfigPayload` fields, it SHALL emit the
`snapsync://config?v=2&d=<base64url(json)>` URL and render a scannable QR-code PNG. The task SHALL
read field values (including the secret) from environment variables and/or a gitignored
`local.properties`, and SHALL NOT commit secrets. The URL it emits SHALL be byte-compatible with
what the pure decoder accepts. The generator SHALL NOT take or emit an upload host/`endpoint`.

#### Scenario: Task emits a decodable URL and a QR image
- **WHEN** the generator task runs with the four fields supplied via env/local.properties
- **THEN** it writes a QR PNG and prints a `snapsync://config?v=2&d=…` URL that the pure decoder
  decodes back to the same four field values

#### Scenario: Secrets are never committed
- **WHEN** the generator reads the secret access key
- **THEN** it is sourced from env or gitignored `local.properties` and no secret is written to a
  tracked file

