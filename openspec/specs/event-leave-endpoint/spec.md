# event-leave-endpoint Specification

## Purpose

The backend half of leaving: `DELETE /api/v1/events/<eventId>/devices/<deviceId>` renames the departing device's
manifest to a departed `.left.json` sibling — its already-shared photos stay downloadable for the remaining
members — and returns `200` regardless of remaining membership. It is **rename-only**: it reaps nothing and
collects nothing.

Before it, leave was local-only: the device forgot the event while its manifest, byte partition, and push
token persisted on the backend forever, so an event's storage could never be reclaimed.

The rename is designed to be **leak-safe rather than atomic**: every partial failure and every race resolves
to an orphan, never to destruction of in-use data. Membership is last-write-wins over the two sibling
manifests' write times, so a stalled leave/rejoin settles on the intended state.

Reclamation belongs entirely to the nightly sweep (capability `scheduled-cleanup`), which deletes an event
past its stamped lifetime **or** one that is empty — ever joined, with no active member left. This route's
rename is therefore what *makes* an event empty, but never what deletes it.

That emptiness path is **opportunistic, not a guarantee**: the client clears its local state and dispatches
this `DELETE` fire-and-forget, best-effort, never retried, so a leave that never reaches storage leaves an
active manifest behind and the event never empties. The same mechanism that produces that abandon-leak is
what prevents a premature emptiness deletion; the stamped lifetime is the only bound that always holds.

Decision record: `changes/archive/2026-07-06-add-event-leave-lifecycle`;
`changes/archive/…-decouple-event-window-from-lifetime` (reclamation moved wholly to the nightly sweep).
## Requirements
### Requirement: Leave route and departed rename

The backend SHALL accept an HTTP `DELETE` at the path template
`/api/v1/events/<eventId>/devices/<deviceId>`
(the literal labels `events` and `devices` are required); both ids MUST match a UUID pattern. A request
whose path does not match (missing a label, wrong depth) SHALL yield `404`; a matched request with a
non-UUID id SHALL yield `400`; neither case SHALL make an upstream request. The route SHALL be gated on
event-marker existence: a `DELETE` for an `eventId` whose `events/<eventId>/metadata.json` marker is
absent SHALL yield `404` without further storage writes. For an existing event the handler SHALL mark
the device **departed** by renaming its active manifest to the departed sibling: it SHALL first `PUT`
`events/<eventId>/devices/<deviceId>.left.json` with the current content of
`events/<eventId>/devices/<deviceId>.json` (minting a **fresh** last-modified time — a read-then-PUT,
never a metadata-preserving server-side copy), then delete the active
`events/<eventId>/devices/<deviceId>.json`. **After the departed rename the handler SHALL return success
(`200`) regardless of how many active members remain; leaving is non-destructive.** It SHALL NOT inspect
remaining membership, SHALL NOT delete the event marker or any other manifest, and SHALL NOT delete or
garbage-collect any bytes, config, or attestation record — the event survives until it expires and is
deleted by the scheduled cleanup (capability `scheduled-cleanup`). All upstream requests SHALL carry the
storage zone's `AccessKey` and never the account API key. A `DELETE` for a device with no active manifest
(already departed, or never a member) SHALL still succeed idempotently.

#### Scenario: Leave renames the active manifest to the departed sibling

- **WHEN** a `DELETE /api/v1/events/<uuid>/devices/<uuid>` arrives for an existing event where the device has an active manifest
- **THEN** the handler writes `events/<eventId>/devices/<deviceId>.left.json` (fresh timestamp) from the active manifest's content, then deletes `events/<eventId>/devices/<deviceId>.json`, and returns `200`

#### Scenario: The last member leaving keeps the event

- **WHEN** the departing device is the only active member, so no active member remains after the rename
- **THEN** the handler still returns `200`, deletes no marker and no bytes, and the event survives (rejoinable) until it expires

#### Scenario: Leave for an absent event marker is rejected

- **WHEN** the `eventId`'s `metadata.json` marker does not exist
- **THEN** the endpoint responds `404` and writes nothing

#### Scenario: Non-UUID ids and unmatched paths rejected without upstream

- **WHEN** either id is not a UUID, or the path/method does not match `DELETE /api/v1/events/<eventId>/devices/<deviceId>`
- **THEN** the endpoint responds `400` (non-UUID) or `404` (unmatched) and makes no upstream request

#### Scenario: Leaving preserves the departed device's contributions

- **WHEN** a device leaves an event that still has other members
- **THEN** the device's photos remain downloadable from the event union (served from its `.left.json` manifest) and none of its bytes are deleted

### Requirement: Idempotent, leak-safe cascade

The handler SHALL be idempotent and safe under at-least-once delivery: a repeated `DELETE` for an
already-departed device SHALL re-run harmlessly (re-writing an existing `.left.json`, deleting an absent
active manifest as a no-op) and SHALL NOT corrupt state. The rename SHALL resolve every partial failure
toward an **orphan** (an undeleted object), never toward deleting in-use data: the departed `.left.json`
is written **before** the active `.json` is deleted, so a failure between them leaves the device
recoverable rather than losing its contributions. The losing-sibling delete is cosmetic — the
last-write-wins comparison, not object absence, determines membership — so no delete-retry is required
for correctness.

#### Scenario: A duplicate leave call is harmless

- **WHEN** a `DELETE` is delivered twice for the same device (a retry after a completed first call)
- **THEN** the second call re-runs the rename with no additional effect and returns success

#### Scenario: A partial rename never loses contributions

- **WHEN** the `.left.json` write succeeds but the `.json` delete fails
- **THEN** the device is treated as departed (the newer `.left.json` wins), its contributions remain in the union, and the leftover `.json` is inert until the event is deleted by the scheduled cleanup

### Requirement: Leave requires a device token

`DELETE /api/v1/events/<eventId>/devices/<deviceId>` SHALL require a valid device token (capability
`device-attestation`) in `Authorization: Bearer`. A request without one SHALL be rejected with `401`, and
the endpoint SHALL NOT read the event marker and SHALL NOT rename any manifest.

The gate SHALL be applied before any state is inspected or touched, so an unauthenticated caller cannot
probe which events exist or alter any membership.

#### Scenario: An unauthenticated leave changes nothing

- **WHEN** `DELETE /api/v1/events/<uuid>/devices/<uuid>` arrives with no valid token
- **THEN** the endpoint responds `401`, reads no marker, and renames no manifest

#### Scenario: An attested leave renames unchanged

- **WHEN** `DELETE /api/v1/events/<uuid>/devices/<uuid>` carries a valid token
- **THEN** the departed rename proceeds and the endpoint returns `200`, idempotently and leak-safely

