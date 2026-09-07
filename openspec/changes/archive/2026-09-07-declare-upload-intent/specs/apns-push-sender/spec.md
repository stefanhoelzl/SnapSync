## MODIFIED Requirements

### Requirement: Silent background push over HTTP/2

For each target token the sender SHALL issue an `HTTP/2` `POST` to `/3/device/<token>` on the selected
host, carrying the headers `apns-topic: <APNS_TOPIC>`, `apns-push-type: background`, `apns-priority: 5`,
and `apns-collapse-id: <eventId>`, with the JSON body
`{ "aps": { "content-available": 1 }, "eventId": "<eventId>" }` (a silent push —
no `alert`, `sound`, or `badge`). The `eventId` is a top-level custom key carrying the event the push
concerns (supplied by the caller — capability `api-endpoints` — from the write that made an asset
fetchable); the `aps` object itself is unchanged. The sender SHALL rely on the runtime `fetch`'s automatic
HTTP/2 negotiation and SHALL NOT require a bespoke HTTP/2 client library or a native dependency. A push
SHALL carry only the transport discriminator's `kind == "apns"` tokens; a non-`apns` token SHALL be skipped.

The collapse identifier SHALL be the **event id**, so that undelivered wakes for one event coalesce into a
single delivery. Two wakes for the same event are interchangeable by construction: a wake carries only its
event, and a recipient responds by reconciling that event's whole union (capability `photo-download`), so
collapsing loses no information. This is why the identifier is the event and not the asset — a per-asset
identifier would preserve a distinction no recipient reads, while spending a delivery per asset against a
throttled allowance.

The sender SHALL NOT set `apns-expiration`. Omitting it leaves APNs storing the notification and retrying,
which is correct for a wake that stays valid for the event's whole life: a late delivery still causes a
useful reconcile. Setting it to `0` would mean a single delivery attempt, so a device briefly offline would
miss a wake it could otherwise have received, and the collapse identifier already bounds what accumulates
to one delivery per event.

`apns-priority` SHALL remain `5`. The background push type requires it; `1` deprioritises further, and `10`
is not permitted for this push type — so priority is not a lever for improving delivery.

#### Scenario: A silent background push is posted

- **WHEN** the sender pushes to an `apns` token with `env` `production` for event `E`
- **THEN** it `POST`s to `https://api.push.apple.com/3/device/<token>` with `apns-topic`,
  `apns-push-type: background`, `apns-priority: 5`, `apns-collapse-id: E`, and body
  `{ "aps": { "content-available": 1 }, "eventId": "E" }`

#### Scenario: The event id rides alongside the aps object

- **WHEN** the sender builds the push body
- **THEN** `eventId` is a top-level sibling of `aps` (delivered to the app as `userInfo["eventId"]`),
  and `aps` still carries only `content-available: 1`

#### Scenario: Undelivered wakes for one event coalesce

- **WHEN** two wakes for event `E` are issued to a device that is not reachable, and the device then
  becomes reachable
- **THEN** APNs delivers one of them, because both carried `apns-collapse-id: E`

#### Scenario: No expiration is sent

- **WHEN** the sender builds the request headers
- **THEN** no `apns-expiration` header is present, so APNs stores and retries the wake
