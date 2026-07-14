## MODIFIED Requirements

### Requirement: Reference-checked garbage collection of freed devices

When a reap deletes an event tree, the handler SHALL determine, for each freed device that had a
manifest in that event, whether that device still appears in any surviving event — as an active `.json`
**or** a departed `.left.json` manifest under some `events/<otherEventId>/devices/` — by listing
surviving events. A device that appears in **no** surviving event is **fully orphaned**, and only then
SHALL the handler delete its byte partition — every object under `files/devices/<deviceId>/`, listed and
deleted one object at a time (there is no batch delete) — its config object `devices/<deviceId>.json`,
and its attestation record `devices/<deviceId>.attest.json` (capability `device-attestation`).
A device that still appears in another surviving event SHALL have its bytes, config, and attestation
record **retained** (another event's union still references the shared partition).

Collecting the attestation record is required for the same reason as the config object: it is per-device
state keyed by a device id that no longer participates in anything. Leaving it behind would leak an
object per departed device forever. It is safe to delete: a device that returns simply attests again,
which writes a fresh record.

#### Scenario: A fully-orphaned device's bytes, config, and attestation record are collected

- **WHEN** an event is reaped and one of its devices appears in no other surviving event
- **THEN** every object under `files/devices/<deviceId>/` is deleted, and `devices/<deviceId>.json` and
  `devices/<deviceId>.attest.json` are deleted

#### Scenario: A device still in another event keeps its bytes

- **WHEN** an event is reaped but one of its devices has a manifest (`.json` or `.left.json`) in another surviving event
- **THEN** that device's `files/devices/<deviceId>/` partition, `devices/<deviceId>.json` config, and
  `devices/<deviceId>.attest.json` attestation record are retained

#### Scenario: A collected device can rejoin

- **WHEN** a device whose attestation record was collected later attests again
- **THEN** the attestation succeeds and a fresh `devices/<deviceId>.attest.json` is written

## ADDED Requirements

### Requirement: Leave requires a device token

`DELETE /events/<eventId>/devices/<deviceId>` SHALL require a valid device token (capability
`device-attestation`) in `Authorization: Bearer`. A request without one SHALL be rejected with `401`, and
the endpoint SHALL NOT read the event marker, SHALL NOT rename any manifest, and SHALL NOT run any part
of the reap or garbage-collection cascade.

This route is the most destructive in the API — it can reap an event tree and delete a device's entire
byte partition — so it SHALL be gated before any state is inspected or touched.

#### Scenario: An unauthenticated leave changes nothing

- **WHEN** `DELETE /events/<uuid>/devices/<uuid>` arrives with no valid token
- **THEN** the endpoint responds `401`, reads no marker, renames no manifest, and deletes nothing

#### Scenario: An attested leave cascades unchanged

- **WHEN** `DELETE /events/<uuid>/devices/<uuid>` carries a valid token
- **THEN** the departed rename, the last-active-member reap, and the reference-checked garbage collection
  proceed exactly as before, idempotently and leak-safely
