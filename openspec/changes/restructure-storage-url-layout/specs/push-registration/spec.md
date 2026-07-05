## MODIFIED Requirements

### Requirement: Token registration writes the device config

The module SHALL provide a `PushRegistration` use-case (in `commonMain`, tested) that, given the
`deviceId` (from the `device-identity` seam), the backend host (injected compile-time base), and a
`pushToken` (`token` + `env` from `PushTokenSource`), performs a `PUT <host>/devices/<deviceId>`
with the JSON body `{ "pushToken": { "kind": "apns", "token": <token>, "env": <env> } }` via an
**injected HTTP client seam** (faked in tests; the real client is the shared Darwin/Ktor client at the
composition root). It SHALL build the request with string-building only — no crypto, no signing — and
SHALL NOT read or require any event id (registration is event-independent). A non-2xx or failed write
SHALL be handled without throwing to the caller (registration is retried on the next trigger; a failed
registration SHALL NOT block join, upload, or download).

#### Scenario: Registration PUTs the config document

- **WHEN** `PushRegistration` runs with `deviceId`, host, and an `apns` token/env
- **THEN** it issues `PUT <host>/devices/<deviceId>` with body `{ "pushToken": { "kind":
  "apns", "token": <token>, "env": <env> } }`

#### Scenario: A failed registration does not disrupt the app

- **WHEN** the config `PUT` returns a non-2xx status or the client errors
- **THEN** the failure is absorbed (no exception to the caller) and the app's join/upload/download are
  unaffected

#### Scenario: Registration carries no event id

- **WHEN** `PushRegistration` builds its request
- **THEN** the URL and body contain no `eventId` (the token is device-scoped, event-independent)
