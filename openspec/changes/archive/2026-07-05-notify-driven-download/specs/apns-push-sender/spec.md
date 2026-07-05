## MODIFIED Requirements

### Requirement: Silent background push over HTTP/2

For each target token the sender SHALL issue an `HTTP/2` `POST` to `/3/device/<token>` on the selected
host, carrying the headers `apns-topic: <APNS_TOPIC>`, `apns-push-type: background`, and `apns-priority:
5`, with the JSON body `{ "aps": { "content-available": 1 }, "eventId": "<eventId>" }` (a silent push —
no `alert`, `sound`, or `badge`). The `eventId` is a top-level custom key carrying the event the push
concerns (supplied by the caller — capability `event-notify-endpoint` — from the notify route path);
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
