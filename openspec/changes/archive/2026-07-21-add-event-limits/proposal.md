# Add Event Limits

## Why

An event today is unbounded: any number of devices can enroll against its QR, and the event —
with every member's photos — lives on the backend forever. For a host-facing product ("share
photos from an event"), both are wrong: an event has a guest list and an end. This change bounds
every new event in size and lifetime, enforced server-side, so an invite QR's blast radius is
finite even when no client cooperates.

## What Changes

- **Every new event is minted with limits stamped on its marker**: `endsAt = startsAt + DURATION`
  and `capacity` (device cap), resolved at `POST /events` time from backend global config
  (env-overridable; initial values: capacity 10, duration 30 days, grace 1 day). Enforcement
  reads the marker's own fields, so a later change can make the values creator-chosen with no
  schema change.
- **Capacity**: enrollment of a device id never seen on the event is rejected with `409 Conflict`
  once the count of distinct device ids ever enrolled (active ∪ departed) has reached
  `capacity`. Leaving does not free a slot; a same-device rejoin reuses its slot. The creator's
  own device counts. Overshoot under concurrent enrollments is accepted (the storage backend has
  no compare-and-set).
- **Duration + grace**: past `endsAt`, no new device may enroll (`410 Gone`), but devices already
  on the event keep full sync — manifest writes, uploads, union reads, notify — for a 1-day grace
  window, so in-window photos that upload late still land.
- **Expiry deletion**: past `endsAt + grace` the event is dead. The first request that touches it
  triggers a lazy reap — enumerate members, fan out the existing silent push, then delete every
  object including the marker (no tombstone). Thereafter the event `404`s exactly like one that
  never existed. There is no scheduler; reap timing rides on member traffic.
- **Legacy markers** (predating `endsAt`/`capacity`) are treated as already past grace: reaped on
  next access. Deliberate pre-release posture; no grandfathering.
- **Zero client change.** Clients keep sending `{name, startsAt}` on create and ignore the new
  marker fields; the new `409`/`410` fall into existing generic failure paths. Guest-facing
  "full"/"ended" UI, creator-chosen limits, and the client-side capture-date upper bound are a
  planned follow-up change, out of scope here.

## Capabilities

### New Capabilities

- `event-limits`: the bounds every event carries — capacity and lifetime stamped on the marker at
  mint from global config; the shared expiry gate every event-scoped route passes (live → grace →
  reaped); the lazy reap-with-notification that deletes an expired event on first touch; the
  status-code contract (`409` capacity, `410` grace-closed enrollment, `404` after deletion).

### Modified Capabilities

- `event-creation`: the marker registry document gains `endsAt` and `capacity`, stamped
  server-side at mint from backend config; the create response and the metadata route return
  them; the legacy read-time `startsAt` synthesis is superseded (a marker without the new fields
  is expired, never served).
- `bunny-upload-endpoint`: the device-manifest write route's event-existence gate becomes the
  limits gate — it additionally distinguishes never-seen device ids and rejects them with `409`
  at capacity and `410` during grace, while known devices' writes stay governed by existence
  only; the route's upstream-call budget gains the `devices/` listing the distinction requires.

No delta for `bunny-list-endpoint`, `event-notify-endpoint`, or `event-leave-endpoint`: their
existing "gated on event existence" requirements are unchanged — with no tombstone, an expired
event *becomes* nonexistent, and the moment of that transition is `event-limits`' own contract.
No delta for `device-manifest` (the client-side manifest document is untouched) or `join-event`
(the client is untouched in this change).

## Impact

- **Backend only** (`backend/src/`): `app.ts` (marker mint, shared gate on the marker-read
  helper, enrollment gating, reap-with-push reusing the leave cascade + notify fan-out),
  `config.ts` (three new env-overridable constants), `validators.ts` (marker field validation),
  plus backend tests.
- **Wire surface**: `POST /events` response and `GET /events/:eventId` gain `endsAt`/`capacity`
  fields (additive; clients ignore them). New responses: `409` on the device `PUT` (capacity),
  `410` on the device `PUT` (grace-closed enrollment). No client, app, or harness code changes.
- **Operational**: events created before this deploys are reaped on next access; existing
  members of such events lose the event (silent push fires, photos already on devices remain).
