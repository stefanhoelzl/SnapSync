# apns-push-sender Specification

## Purpose

The backend's APNs client: token-based ES256 provider authentication (no certificate to rotate),
environment-selected host, and a **silent** background push delivered over HTTP/2 to one device token.

Silent — `content-available`, no alert, no sound — because the push exists to *wake* a member device so it
can pull new photos, never to interrupt the user. Delivery is reported **per token and best-effort**: APNs
can reject an individual token (expired, unregistered) without that being a failure of the fan-out, so the
sender surfaces each outcome rather than collapsing them into one verdict. APNs is the **iOS binding of
the platform-neutral wake-a-member need**; a future Android client would bind FCM behind the same
per-token, best-effort sender seam, with the caller (`api-endpoints`) unchanged.

The APNs signing key (`APNS_PRIVATE_KEY`) is one of the backend's two environment **secrets**, fail-closed at startup; the key id and team id are resolved from the deployment and the topic is DERIVED from the bundle id (capabilities `deployment-configuration`, `backend-deployment`). The caller that decides
*when* to send is `api-endpoints`.

Decision record: `changes/archive/2026-07-05-push-notification-infra`.

## Requirements
### Requirement: Token-based ES256 provider authentication

The sender SHALL authenticate to APNs with **token-based** (provider JWT) authentication, not
certificates. It SHALL sign a JWT with **ES256** over the header `{ "alg": "ES256", "kid": <APNS_KEY_ID>
}` and claims `{ "iss": <APNS_TEAM_ID>, "iat": <now-seconds> }`, using the `.p8` private key
(`APNS_PRIVATE_KEY`), and send it as `authorization: bearer <jwt>`. The signed token MAY be cached and
reused across sends for **at most one hour** (re-signed when older); the sender SHALL NOT sign a fresh
JWT per push. Signing SHALL use the runtime's WebCrypto (or an equivalent runtime-agnostic primitive)
with **no native dependency**, so it runs unchanged on every backend deployment target.

#### Scenario: A JWT bearer token authorizes the send

- **WHEN** the sender issues an APNs request
- **THEN** the request carries `authorization: bearer <jwt>` where the JWT is ES256-signed with `kid`
  = the configured key id and `iss` = the configured team id

#### Scenario: The provider token is reused within its lifetime

- **WHEN** two pushes are sent a few seconds apart
- **THEN** the same signed JWT is reused (no re-sign), and a JWT older than one hour is re-signed
  before use

### Requirement: APNs environment selects the host

The sender SHALL choose the APNs host from the token's `env`: `"production"` →
`https://api.push.apple.com`, `"sandbox"` → `https://api.sandbox.push.apple.com`. A token whose `env`
is neither SHALL be treated as unsendable and skipped (no request), not sent to a default host.

#### Scenario: Production token targets the production host

- **WHEN** a token's `env` is `"production"`
- **THEN** the request targets `api.push.apple.com`

#### Scenario: Sandbox token targets the sandbox host

- **WHEN** a token's `env` is `"sandbox"`
- **THEN** the request targets `api.sandbox.push.apple.com`

#### Scenario: Unknown env is skipped

- **WHEN** a token's `env` is absent or an unrecognized value
- **THEN** the sender makes no request for that token and reports it as unsent

### Requirement: Silent background push over HTTP/2

For each target token the sender SHALL issue an `HTTP/2` `POST` to `/3/device/<token>` on the selected
host, carrying the headers `apns-topic: <APNS_TOPIC>`, `apns-push-type: background`, and `apns-priority:
5`, with the JSON body `{ "aps": { "content-available": 1 }, "eventId": "<eventId>" }` (a silent push —
no `alert`, `sound`, or `badge`). The `eventId` is a top-level custom key carrying the event the push
concerns (supplied by the caller — capability `api-endpoints` — from the notify route path);
the `aps` object itself is unchanged. The sender SHALL rely on the runtime `fetch`'s automatic HTTP/2
negotiation and SHALL NOT require a bespoke HTTP/2 client library or a native dependency. A push SHALL
carry only the transport discriminator's `kind == "apns"` tokens; a non-`apns` token SHALL be skipped.

#### Scenario: A silent background push is posted

- **WHEN** the sender pushes to an `apns` token with `env` `production` for event `E`
- **THEN** it `POST`s to `https://api.push.apple.com/3/device/<token>` with `apns-topic`,
  `apns-push-type: background`, `apns-priority: 5`, and body
  `{ "aps": { "content-available": 1 }, "eventId": "E" }`

#### Scenario: The event id rides alongside the aps object

- **WHEN** the sender builds the push body
- **THEN** `eventId` is a top-level sibling of `aps` (delivered to the app as `userInfo["eventId"]`),
  and `aps` still carries only `content-available: 1`

#### Scenario: Non-apns token skipped

- **WHEN** a target token's `kind` is not `"apns"`
- **THEN** the sender makes no request for it and reports it as unsent

### Requirement: Per-token outcome, best-effort

The sender SHALL report each token's outcome to its caller (delivered vs failed, with the APNs status
where available) and SHALL isolate failures per token — one token's error, timeout, or APNs rejection
(e.g. `400 BadDeviceToken`, `410 Unregistered`) SHALL NOT abort the remaining sends. The sender itself
SHALL NOT throw out of a fan-out; it returns outcomes. (Pruning `410`/`BadDeviceToken` stale tokens
from storage is out of scope for this capability.)

#### Scenario: One bad token does not stop the others

- **WHEN** a batch of tokens is sent and one returns `410 Unregistered`
- **THEN** the other tokens are still attempted and each token's outcome is reported

#### Scenario: A rejection is reported, not thrown

- **WHEN** APNs returns a non-2xx status for a token
- **THEN** the sender records that token's failure status and returns normally (no exception propagates
  to the caller)

