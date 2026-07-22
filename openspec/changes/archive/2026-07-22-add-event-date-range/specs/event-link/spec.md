# event-link Specification

## MODIFIED Requirements

### Requirement: Event link URL and payload contract

The system SHALL define an HTTPS **Universal Link** whose event link has the form
`https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object whose **required** key is `eventId` (a string) and whose
**optional** keys are `autoJoin` (a boolean), `minPhotoDate` (a string), `maxPhotoDate` (a string),
`direction` (a string), and `saveToAlbum` (a boolean) — the event-link wire payload
(`EventLinkPayload`). The `eventId` SHALL be a high-entropy **canonical UUID**; possession of it is the
upload capability (the edge endpoint authorizes by event id alone). `autoJoin` SHALL default to `false`
when absent; it is a **dev/test** hint that requests the join gate auto-confirm (see capability
`join-event`). `minPhotoDate` SHALL be absent by default; it is a **dev/test** capture-date cutoff (a UTC
`yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability `photo-selection-policy`) that, on an auto-confirmed join,
forces a specific cutoff so a headless launch can observe date filtering. `maxPhotoDate` SHALL be absent by
default; it is a **dev/test** capture-date **ceiling** (a UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability
`photo-selection-policy`), mirroring `minPhotoDate` from the top: on an auto-confirmed join it forces a
specific upper bound so a headless launch can observe the upper-bound filtering. Like `minPhotoDate` is
clamped to the event floor, `maxPhotoDate` SHALL be clamped to the event `endsAt` on the far side (in
`JoinEvent`, capability `join-event`), so a hostile-link value is always bounded to the event window.
`direction` SHALL be absent by default; it is a **dev/test** participation-direction override — one of
`both`, `upload`, or `download` — that, on an auto-confirmed join, forces the joined membership's direction
(see capability `join-event`). `saveToAlbum` SHALL be absent by default; it is a **dev/test** override (a
boolean) that, on an auto-confirmed join, forces whether the membership gathers its synced photos into an
event album (capability `event-album`), so a headless launch can exercise album placement without an
interactive tap. None of `autoJoin`, `minPhotoDate`, `maxPhotoDate`, `direction`, or `saveToAlbum` SHALL be
emitted by the canonical QR encoder — real invite QRs carry `eventId` only. The upload **host** SHALL NOT
appear in the payload: it is fixed at compile time by the extension's `BackgroundUploadURLBase` (capability
`ios-photokit-upload`). The payload carries **no storage credential** and **no event name** — the name is
not carried in the QR; a joined device fetches it by `eventId` (see *Event name is fetched, not carried in
the event link*). `v` SHALL be the integer format version, `3` (the optional `autoJoin`, `minPhotoDate`,
`maxPhotoDate`, `direction`, and `saveToAlbum` keys are additive within `v=3` and do not bump the version;
the migration from the retired `snapsync://config?…` form did not bump it either, because the payload is
unchanged and the URL prefix already distinguishes the forms).

The payload SHALL be carried entirely in the link's **fragment** — never the query string. Because a
browser never transmits the fragment component to a server, the `eventId`, which is the upload
capability, never reaches the backend, its CDN, or their access logs, even when the link is opened on a
device that does not have the app. Under the retired `snapsync://` scheme this property was incidental —
no server could observe a custom-scheme URL at all — but under a Universal Link it is **deliberate and
purchased**, and moving the payload to the query string would silently forfeit it. There is no server
round-trip and no token: the link remains self-contained.

#### Scenario: Canonical event URL shape
- **WHEN** an event link is constructed for an `EventLinkPayload` with no optional keys
- **THEN** it is `https://snapsync.stho.net/join#v=3&d=<base64url(json)>` and the decoded JSON has the
  string key `eventId` and no other keys (no `autoJoin`, no `minPhotoDate`, no `maxPhotoDate`, no
  `direction`, no `saveToAlbum`, no `name`, no host, no credential)

#### Scenario: The payload rides in the fragment, not the query
- **WHEN** an event link is constructed for any `EventLinkPayload`
- **THEN** everything after `/join` is carried after `#`, and the URL's query component is empty — so a
  request for the link's path carries no payload

#### Scenario: A dev link carries the autoJoin flag
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true }`
- **THEN** the decode succeeds carrying that `eventId` and `autoJoin == true`

#### Scenario: A dev link carries an explicit cutoff
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "minPhotoDate": "2026-07-06T14:32:11Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `minPhotoDate` cutoff string

#### Scenario: A dev link carries an explicit capture-date ceiling
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "maxPhotoDate": "2026-07-21T23:59:59Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `maxPhotoDate` ceiling string

#### Scenario: A dev link carries an explicit direction override
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "direction": "download" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `direction` override `download`

#### Scenario: A dev link carries an explicit saveToAlbum override
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "saveToAlbum": true }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `saveToAlbum` override `true`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw event-link URL string into either a valid `EventLinkPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the URL begins with the
canonical origin and path (`https://snapsync.stho.net/join#`, matched against the single-sourced
`LINK_ORIGIN` constant); `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive), and **at most** the additional optional keys `autoJoin` (a boolean),
`minPhotoDate` (a non-empty string), `maxPhotoDate` (a non-empty string), `direction` (one of the strings
`both`, `upload`, `download`), and `saveToAlbum` (a boolean). Any deviation — including a retired
`snapsync://` URL, a foreign origin, a version other than `3` (so the v1/v2 S3 payloads are rejected), a
missing/empty `eventId`, a **genuinely unknown** key (anything other than
`eventId`/`autoJoin`/`minPhotoDate`/`maxPhotoDate`/`direction`/`saveToAlbum`), a `direction` outside the
allowed set, or an `eventId` that is not a canonical UUID — SHALL produce a typed
failure result, never a partially-populated payload and never a thrown exception that escapes the
decoder. On success the result SHALL carry the `eventId`, the resolved `autoJoin` value (defaulting to
`false`), the `minPhotoDate` cutoff when present (else absent), the `maxPhotoDate` ceiling when present
(else absent), the `direction` override when present (else absent), and the `saveToAlbum` override when
present (else absent).

The decoder SHALL match the origin **strictly**, as a single prefix, and SHALL NOT parse the URL with a
structured URL type. A foreign origin cannot reach the decoder in production — `.onOpenURL` fires for a
Universal Link only when the app's own entitlement names the domain, and the dev launch-environment
trigger requires a developer launch — so strict matching is chosen for being *less* code than searching
for the path inside an arbitrary string, not as a security control.

#### Scenario: Well-formed payload decodes
- **WHEN** a `https://snapsync.stho.net/join#v=3&d=…` URL whose payload carries a single non-empty
  canonical-UUID `eventId` is decoded
- **THEN** the result is a success carrying the `EventLinkPayload` with that exact `eventId`, `autoJoin == false`, no `minPhotoDate`, no `maxPhotoDate`, no `direction`, and no `saveToAlbum`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a foreign origin, a wrong path, a version other than `3` (including a legacy `v=2`
  S3 payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a genuinely unknown key
  (other than `eventId`/`autoJoin`/`minPhotoDate`/`maxPhotoDate`/`direction`/`saveToAlbum`), a `direction`
  outside `both`/`upload`/`download`, or an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventLinkPayload`, without throwing

#### Scenario: A retired snapsync:// link is rejected
- **WHEN** a `snapsync://config?v=3&d=…` URL is decoded
- **THEN** the decoder returns a typed failure, so a link shared before the migration fails closed and
  visibly rather than provisioning

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 payload (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" create layer and the user rescans the new event QR
