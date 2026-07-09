# event-leave-endpoint Specification

## Purpose

The backend half of leaving: `DELETE /events/<eventId>/devices/<deviceId>` renames the departing device's
manifest to a departed `.left.json` sibling — its already-shared photos stay downloadable for the remaining
members — and when the **last** active member leaves, reaps the event tree and reference-checked-garbage-
collects each freed device's byte partition and config.

Before it, leave was local-only: the device forgot the event while its manifest, byte partition, and push
token persisted on the backend forever, so an event's storage could never be reclaimed.

The cascade is designed to be **leak-safe rather than atomic**: every partial failure and every race resolves
to an orphan, never to destruction of in-use data. Membership is last-write-wins over the two sibling
manifests' write times, so a stalled leave/rejoin settles on the intended state. The one real correctness
premise is that the reap's active-member listing reads the storage **main region** — a stale replica read
could reap an event out from under a concurrently-rejoining device.

There is deliberately **no periodic reaper**: an event whose devices all vanish without a clean leave
(uninstall, permanent offline) is never reclaimed. That abandon-leak is accepted.

Decision record: `changes/archive/2026-07-06-add-event-leave-lifecycle`.

## Requirements
### Requirement: Leave route and departed rename

The backend SHALL accept an HTTP `DELETE` at the path template `/events/<eventId>/devices/<deviceId>`
(the literal labels `events` and `devices` are required); both ids MUST match a UUID pattern. A request
whose path does not match (missing a label, wrong depth) SHALL yield `404`; a matched request with a
non-UUID id SHALL yield `400`; neither case SHALL make an upstream request. The route SHALL be gated on
event-marker existence: a `DELETE` for an `eventId` whose `events/<eventId>/metadata.json` marker is
absent SHALL yield `404` without further storage writes. For an existing event the handler SHALL mark
the device **departed** by renaming its active manifest to the departed sibling: it SHALL first `PUT`
`events/<eventId>/devices/<deviceId>.left.json` with the current content of
`events/<eventId>/devices/<deviceId>.json` (minting a **fresh** last-modified time — a read-then-PUT,
never a metadata-preserving server-side copy), then delete the active
`events/<eventId>/devices/<deviceId>.json`. All upstream requests SHALL carry the storage zone's
`AccessKey` and never the account API key. A `DELETE` for a device with no active manifest (already
departed, or never a member) SHALL still succeed idempotently.

#### Scenario: Leave renames the active manifest to the departed sibling

- **WHEN** a `DELETE /events/<uuid>/devices/<uuid>` arrives for an existing event where the device has an active manifest
- **THEN** the handler writes `events/<eventId>/devices/<deviceId>.left.json` (fresh timestamp) from the active manifest's content, then deletes `events/<eventId>/devices/<deviceId>.json`

#### Scenario: Leave for an absent event marker is rejected

- **WHEN** the `eventId`'s `metadata.json` marker does not exist
- **THEN** the endpoint responds `404` and writes nothing

#### Scenario: Non-UUID ids and unmatched paths rejected without upstream

- **WHEN** either id is not a UUID, or the path/method does not match `DELETE /events/<eventId>/devices/<deviceId>`
- **THEN** the endpoint responds `400` (non-UUID) or `404` (unmatched) and makes no upstream request

#### Scenario: Leaving preserves the departed device's contributions

- **WHEN** a device leaves an event that still has other members
- **THEN** the device's photos remain downloadable from the event union (served from its `.left.json` manifest) and none of its bytes are deleted

### Requirement: Last-active-member reap

After the departed rename, the handler SHALL `LIST` `events/<eventId>/devices/` and determine whether
any **active** member remains, where a device is active when its `<deviceId>.json` is present and
(its `<deviceId>.left.json` sibling is absent or its `<deviceId>.json` is the newer of the two by
last-modified time). If **no** active member remains, the handler SHALL delete the entire
`events/<eventId>/` tree — the `metadata.json` marker and every `<deviceId>.json` / `<deviceId>.left.json`
manifest under `events/<eventId>/devices/` — before returning. If any active member remains, the event
tree SHALL be left intact.

#### Scenario: The last active member leaving deletes the event

- **WHEN** a leave leaves `events/<eventId>/devices/` with no active member (only `.left.json` siblings, or empty)
- **THEN** the handler deletes `events/<eventId>/metadata.json` and every manifest under `events/<eventId>/devices/`

#### Scenario: A leave with active members remaining keeps the event

- **WHEN** at least one active `<deviceId>.json` remains after the departed rename
- **THEN** the event tree is left intact and no bytes are collected

### Requirement: Reference-checked garbage collection of freed devices

When a reap deletes an event tree, the handler SHALL determine, for each freed device that had a
manifest in that event, whether that device still appears in any surviving event — as an active `.json`
**or** a departed `.left.json` manifest under some `events/<otherEventId>/devices/` — by listing
surviving events. A device that appears in **no** surviving event is **fully orphaned**, and only then
SHALL the handler delete its byte partition — every object under `files/devices/<deviceId>/`, listed and
deleted one object at a time (there is no batch delete) — and its config object `devices/<deviceId>.json`.
A device that still appears in another surviving event SHALL have its bytes and config **retained**
(another event's union still references the shared partition).

#### Scenario: A fully-orphaned device's bytes and config are collected

- **WHEN** an event is reaped and one of its devices appears in no other surviving event
- **THEN** every object under `files/devices/<deviceId>/` is deleted and `devices/<deviceId>.json` is deleted

#### Scenario: A device still in another event keeps its bytes

- **WHEN** an event is reaped but one of its devices has a manifest (`.json` or `.left.json`) in another surviving event
- **THEN** that device's `files/devices/<deviceId>/` partition and `devices/<deviceId>.json` config are retained

### Requirement: Idempotent, leak-safe cascade

The handler SHALL be idempotent and safe under at-least-once delivery: a repeated `DELETE` for an
already-departed or already-reaped device/event SHALL re-run harmlessly (re-writing an existing
`.left.json`, deleting absent objects as no-ops, re-checking reap/GC) and SHALL NOT corrupt state. Every
partial failure SHALL resolve toward an **orphan** (an undeleted object), never toward deleting in-use
data: the departed `.left.json` is written **before** the active `.json` is deleted, so a failure between
them leaves the device recoverable rather than losing its contributions. The losing-sibling deletes are
cosmetic — the last-write-wins comparison, not object absence, determines membership — so no delete-retry
is required for correctness.

#### Scenario: A duplicate leave call is harmless

- **WHEN** a `DELETE` is delivered twice for the same device (a retry after a completed first call)
- **THEN** the second call re-runs the cascade with no additional effect and returns success

#### Scenario: A partial rename never loses contributions

- **WHEN** the `.left.json` write succeeds but the `.json` delete fails
- **THEN** the device is treated as departed (the newer `.left.json` wins), its contributions remain in the union, and the leftover `.json` is inert until swept at event death

### Requirement: Reap correctness depends on main-region read-after-write reads

The reap's active-member `LIST` and the GC's surviving-event checks SHALL read the storage zone's
**main** region (the documented `BUNNY_STORAGE_HOST` deployment invariant), so a concurrent rejoin's
freshly-written active `<deviceId>.json` is visible and the reap cannot delete an event out from under an
actively-rejoined device. The reap SHALL NOT be served from an asynchronously-replicated replica
endpoint.

#### Scenario: A concurrent rejoin is not reaped away

- **WHEN** a device rejoins an event (writes a fresh active `<deviceId>.json`) while another device's leave triggers a reap check
- **THEN** the reap's main-region `LIST` observes the fresh active manifest and leaves the event intact

