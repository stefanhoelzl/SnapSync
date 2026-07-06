## MODIFIED Requirements

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
