## MODIFIED Requirements

### Requirement: Token registration writes the device config

The system SHALL provide a `PushRegistration` use-case (`:domain` `feature/push`, `commonMain`,
tested — re-homed from the deleted `:capability:push` at the migration finale) that, given a
`pushToken` (`token` + `env` from `PushTokenSource`), publishes it through the need-named
**`PushTokenPublisher`** port (`:domain` `ports/`: "publish this device's push token"). The port's HTTP
adapter (`:adapter:generic:app`, over the shared Darwin/Ktor client at the composition root) SHALL,
given the `deviceId` (from the `device-identity` seam, as a supplier) and the backend host (injected
compile-time base), perform a `PUT <host>/devices/<deviceId>` with the JSON body
`{ "pushToken": { "kind": "apns", "token": <token>, "env": <env> } }`, and SHALL answer a non-2xx
status or a transport failure as a failed result, never a throw. The adapter SHALL build the request with
string-building only — no crypto, no signing — and neither the adapter nor `PushRegistration` SHALL read
or require any event id (registration is event-independent). `PushRegistration` SHALL own the policy: a
failed publish SHALL be handled without throwing to the caller (registration is retried on the next
trigger; a failed registration SHALL NOT block join, upload, or download). The port's promises are
contracted in `:test:contracts` (capability `port-contracts`) against the real edge and the world's
mini-edge; `PushRegistration`'s policy is tested over a fake port.

#### Scenario: Registration PUTs the config document

- **WHEN** `PushRegistration` runs with an `apns` token/env and the HTTP adapter is bound with
  `deviceId` and host
- **THEN** the adapter issues `PUT <host>/devices/<deviceId>` with body `{ "pushToken": { "kind":
  "apns", "token": <token>, "env": <env> } }`

#### Scenario: A failed registration does not disrupt the app

- **WHEN** the config `PUT` returns a non-2xx status or the client errors
- **THEN** the adapter answers a failed result, `PushRegistration` absorbs it (no exception to the
  caller), and the app's join/upload/download are unaffected

#### Scenario: Registration carries no event id

- **WHEN** the adapter builds its request
- **THEN** the URL and body contain no `eventId` (the token is device-scoped, event-independent)

#### Scenario: The real edge accepts a registration for an enrolled device

- **WHEN** the port contract runs against the real `api` for a device the edge holds an enrolment for
- **THEN** the publish succeeds, and a rejected credential yields a failed result instead
