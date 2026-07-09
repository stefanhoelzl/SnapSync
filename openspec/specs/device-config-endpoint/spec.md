# device-config-endpoint Specification

## Purpose

The backend's per-device configuration document — today, exactly one field: the device's APNs push token,
stored at `devices/<deviceId>/config.json` in the device-partitioned namespace `device-namespace-reorg`
reserved for it.

It is the registry `event-notify-endpoint` reads to turn "every member of this event" into "every token to
push". Authorization is **by device id alone**: the id is a high-entropy UUID minted in the shared Keychain,
so possession of it is the capability — the same model the upload and listing routes use. Writes are
last-write-wins, and the document is deleted when the device becomes fully orphaned, so a departed device
leaves no token behind.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.

## Requirements
### Requirement: Device config write route

The backend SHALL accept an HTTP `PUT` at the path template `/devices/<deviceId>` (the literal
label `devices` is required) whose JSON body is the device's config document, and write
it into bunny native Storage at the bare key `devices/<deviceId>.json` with `Content-Type:
application/json`. `deviceId` MUST match a UUID pattern. A request whose path does not match this route
(missing a label, wrong depth) SHALL yield `404`; a matched request whose `deviceId` is not a UUID
SHALL yield `400`; neither case SHALL make an upstream request. A request using any method other than
`PUT` on this path SHALL yield `404` (no matching route). This route SHALL be served by the same
application as the upload/list endpoints. The config object lives under the device's own namespace and
is **not** a member of the `files/devices/<deviceId>/` byte partition, so it never appears in the
per-device file listing or the event union.

#### Scenario: Valid config write accepted

- **WHEN** a `PUT /devices/<uuid>` arrives with a valid UUID and a JSON body
- **THEN** the endpoint writes the body to the storage key `devices/<uuid>.json` with
  `Content-Type: application/json`

#### Scenario: Non-UUID device id rejected

- **WHEN** the `deviceId` segment is not a UUID
- **THEN** the endpoint responds `400` and makes no upstream request

#### Scenario: Unmatched path or wrong method rejected

- **WHEN** the path does not match `/devices/<deviceId>`, or the method is not `PUT`
- **THEN** the endpoint responds `404` and makes no upstream request

#### Scenario: Config object is not a listed file

- **WHEN** a device has written `devices/<uuid>.json` and its byte partition is later listed
- **THEN** the config object does not appear in `GET /files/devices/<uuid>` nor in any event union
  (it is outside the `files/devices/<uuid>/` partition)

### Requirement: Config document shape — push token

The config document SHALL be a JSON object carrying a `pushToken` field, itself an object with exactly
`kind`, `token`, and `env`: `kind` a transport discriminator (the value `"apns"` in this change),
`token` the provider device-token string, and `env` one of `"sandbox"` | `"production"` naming the
APNs environment the token belongs to. The `pushToken.kind` discriminator SHALL allow a future
transport to be added without reshaping the document. The endpoint SHALL persist the document body as
received (it is the device's self-asserted registration); it SHALL NOT mint or transform the token.

#### Scenario: Push-token document persisted

- **WHEN** a `PUT /devices/<uuid>` body is `{ "pushToken": { "kind": "apns", "token": "<hex>",
  "env": "sandbox" } }`
- **THEN** the stored `devices/<uuid>.json` carries that `pushToken` object verbatim

### Requirement: Authorization by device id only

The config write is addressed by the device-id path alone — the endpoint SHALL NOT require any
authorization token, and SHALL NOT consult any event id or event marker (a device's config is
event-independent). Possession of the (unguessable, per-install) `deviceId` is the capability, the same
trust model as the byte-upload route that writes `files/devices/<deviceId>/…`. The endpoint SHALL NOT
expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `PUT /devices/<uuid>` carries a valid device id and body but no authorization token
- **THEN** the write is accepted (the device id is the capability)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint writes the config object
- **THEN** no response or upstream-facing surface exposes the bunny account API key

### Requirement: Last-write-wins and faithful outcome

The endpoint SHALL write the config as a single unconditional `PUT` with no prior existence check on
the object key; a write to an existing `devices/<deviceId>.json` overwrites it (the latest
registration wins — a rotated token replaces the prior one). It SHALL return a `2xx` **only** when
bunny confirms the object was stored; any upstream error, timeout, or partial write SHALL be surfaced
as `5xx` and SHALL NEVER be reported as `2xx`.

#### Scenario: Existing config overwritten

- **WHEN** a `PUT` targets a `devices/<deviceId>.json` key that already exists
- **THEN** the endpoint issues the upstream `PUT` directly (no prior existence check) and the object is
  overwritten with the new document

#### Scenario: Upstream failure propagated as 5xx

- **WHEN** bunny returns an error, the request times out, or the stream aborts
- **THEN** the endpoint responds `5xx` and never `2xx`

### Requirement: Config removed when the device is fully orphaned

The config object `devices/<deviceId>.json` SHALL be deleted as part of the leave cascade's garbage
collection (see `event-leave-endpoint`) when, and only when, the device becomes **fully orphaned** — it
appears in no surviving event as either an active `<deviceId>.json` or a departed `<deviceId>.left.json`
manifest. There SHALL be no dedicated config-delete HTTP route: the config is removed by the same cascade
that deletes the device's `files/devices/<deviceId>/` byte partition, so a device's config outlives its
byte partition only transiently (both go together). A device that later reinstalls or rejoins re-registers
its config via the existing `PUT` (the device id is Keychain-stable), so config deletion is not
destructive to a returning device.

#### Scenario: Orphaned device's config is collected with its bytes

- **WHEN** the leave cascade determines a device appears in no surviving event
- **THEN** it deletes `devices/<deviceId>.json` together with every object under `files/devices/<deviceId>/`

#### Scenario: Config retained while the device is still in an event

- **WHEN** an event is reaped but the device still has a manifest in another surviving event
- **THEN** `devices/<deviceId>.json` is retained (the device is not orphaned)

