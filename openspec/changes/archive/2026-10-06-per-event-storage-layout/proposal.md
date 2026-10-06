# Proposal

## Why

Photo bytes are stored per device (`files/devices/<deviceId>/…`), once for every event that declares them.
That buys little: SnapSync's events are short and a device is in one at a time, so a byte is seldom shared
between two events. It costs a lot, though. No event owns its bytes, so an event cannot get its own
encryption key, and deleting an event's photos is a per-byte reference count across every event instead
of removing one prefix. This change gives each event its own bytes now, while there is only one zone and
a small installed base, so per-event encryption can follow as its own change.

## What Changes

- **Each event stores its own copy of a photo.** New bytes land under the event (`files/<eventId>/<hash>`)
  with an opaque name. A photo shared to two overlapping events is uploaded once per event. It is still
  never received twice, and it is still not uploaded again on a rejoin, a switch away and back, or a
  reinstall within the same event.
- **Only a present member writes into an event.** The new upload route names the event, and it refuses a
  device that is not sharing or settled in it.
- **Every route writes the new layout.** Builds already installed, and v1, keep their event-less upload
  route. The backend files those uploads under the device's one present membership, so no new byte goes to
  the old layout and no update is forced.
- **The stored path is recorded, not composed.** Each resource row carries its full storage path, and
  every read follows it. The old device-wide rows are migrated in place into per-event rows that still
  point at their old bytes, so photos already shared keep arriving with no gap.
- **An event's photos are deleted as one folder.** Rows go with the membership. The sweep deletes the
  event's folder, and a nightly check removes any event folder with no live event behind it.
- Follow-ups outside this change: a one-off local copy of the remaining old-layout bytes into their
  events' folders; then, at least 7 days later, removal of `files/devices/` and the legacy sweep phases.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-sharing`: "A photo already in the event is not uploaded again" is replaced by "A photo already in
  an event is not uploaded again for it", which stops promising that a photo shared to one event is not
  uploaded again for another event it also falls inside. The rejoin, switch-back, reinstall and
  no-second-copy promises stay.

## Impact

- **api/**: migration `0010` rebuilds `resources` as event-scoped (`event_id`, `path`; drops `key` and
  `UNIQUE(device_id, key)`; FK to `memberships`). A new upload route
  `PUT /api/v2/events/<e>/files/devices/<d>/<a>/<r>` and a new per-event device listing. The old v2 byte
  route and listing, and v1's byte route, resolve the present membership. The union, the download
  redirect and v1's presigns read `path`, and the wire `key` is derived. Sweep: per-event folder delete,
  a reconcile of orphan event folders, and the legacy byte collection suspended. `schema.sql`, the dev
  filesystem store and every affected test change too.
- **Client**: `EdgeUploadRequestProvider` builds the event-scoped URL (the cycle already has the event).
  The join-time load reads the per-event listing (`HttpBackend`, `DeviceFilesSource`, `GatedPaths`).
  `BackendMock` follows the new rules. Jobs already queued with the old URL complete through the ledger's
  recorded destination path, unchanged.
- **Docs**: `docs/architecture.md` §8 (`resources`, routes table, sweep) and §9 (byte store) and
  `docs/deployment.md` §3 (sweep). The `openspec/config.yaml` context line saying bytes are already
  event-independent, since that is now false.
- **Data**: no byte moves in this change. Old-layout bytes stay in place and are served through their
  migrated rows.
